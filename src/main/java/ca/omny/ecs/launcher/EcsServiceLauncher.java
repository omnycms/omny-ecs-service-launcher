package ca.omny.ecs.launcher;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.ecs.AmazonECSClient;
import com.amazonaws.services.ecs.model.ContainerDefinition;
import com.amazonaws.services.ecs.model.CreateServiceRequest;
import com.amazonaws.services.ecs.model.CreateServiceResult;
import com.amazonaws.services.ecs.model.DeleteServiceRequest;
import com.amazonaws.services.ecs.model.DescribeServicesRequest;
import com.amazonaws.services.ecs.model.DescribeServicesResult;
import com.amazonaws.services.ecs.model.ListServicesRequest;
import com.amazonaws.services.ecs.model.ListServicesResult;
import com.amazonaws.services.ecs.model.RegisterTaskDefinitionRequest;
import com.amazonaws.services.ecs.model.RegisterTaskDefinitionResult;
import com.amazonaws.services.ecs.model.Service;
import com.amazonaws.services.ecs.model.UpdateServiceRequest;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.util.IOUtils;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EcsServiceLauncher {

    AmazonSQSClient sqs = new AmazonSQSClient();
    AmazonS3Client s3 = new AmazonS3Client();
    AmazonSNSClient sns = new AmazonSNSClient();
    AmazonDynamoDBClient dynamo = new AmazonDynamoDBClient();
    AmazonECSClient ecs = new AmazonECSClient();
    int WAIT_TIME = 20000;
    int RETRIES = 3;

    private final static String DYNAMO_HASH_KEY_NAME = "table";
    private final static String DYNAMO_RANGE_KEY_NAME = "key";

    public void run(String queue, String cluster, String bucket, String db, boolean usePrivateIp) {
        EcsTaskTracker ecsTaskTracker = new EcsTaskTracker(usePrivateIp);
        Gson gson = new Gson();
        MustacheFactory mf = new DefaultMustacheFactory();

        while (true) {
            try {
                ReceiveMessageResult receiveMessage = sqs.receiveMessage(queue);
                for (Message message : receiveMessage.getMessages()) {
                    //register task
                    String body = message.getBody();
                    Map m = gson.fromJson(message.getBody(), Map.class);
                    ServiceUpdateRequest request;
                    if (m.containsKey("Message")) {
                        request = gson.fromJson(m.get("Message").toString(), ServiceUpdateRequest.class);
                    } else {
                        request = gson.fromJson(message.getBody(), ServiceUpdateRequest.class);
                    }
                    S3Object taskDefinitionObject = s3.getObject(bucket, request.getSite() + "/" + request.getServiceName() + "/container-definition.json");
                    String taskDefinitionString = IOUtils.toString(taskDefinitionObject.getObjectContent());
                    StringWriter writer = new StringWriter();
                    HashMap<String, Object> scopes = new HashMap<>();
                    scopes.put("buildNumber", request.getBuildNumber());
                    String serviceAndBuildNumber = request.getServiceName() + "-" + request.getBuildNumber();
                    Mustache mustache = mf.compile(new StringReader(taskDefinitionString), serviceAndBuildNumber);
                    mustache.execute(writer, scopes);

                    String family = request.getServiceName();
                    String newTaskDefinitionString = writer.toString();
                    ContainerDefinition definition = gson.fromJson(newTaskDefinitionString, ContainerDefinition.class);
                    RegisterTaskDefinitionResult registerTaskDefinition = ecs.registerTaskDefinition(
                            new RegisterTaskDefinitionRequest()
                            .withContainerDefinitions(definition)
                            .withFamily(family)
                    );
                    int taskDefinitionRevision = registerTaskDefinition.getTaskDefinition().getRevision();

                    String serviceAndTaskRevision = request.getServiceName() + "-" + taskDefinitionRevision;
                    //create ECS service
                    CreateServiceResult createService = ecs.createService(
                            new CreateServiceRequest()
                            .withCluster(cluster)
                            .withDesiredCount(request.getNumToRun())
                            .withServiceName(serviceAndTaskRevision)
                            .withTaskDefinition(registerTaskDefinition.getTaskDefinition().getTaskDefinitionArn())
                    );

                    //wait for running
                    createService.getService().getRunningCount();
                    //health check
                    boolean healthCheck = healthCheck(request.getHealthCheckPath(), ecsTaskTracker, createService.getService(), family, taskDefinitionRevision);
                    if (!healthCheck) {
                        Logger.getLogger(EcsServiceLauncher.class.getName()).log(Level.SEVERE, "health check failed for " + request.getServiceName());
                        
                        ecs.updateService(new UpdateServiceRequest()
                                .withCluster(cluster)
                                .withDesiredCount(0)
                                .withService(createService.getService().getServiceArn())
                        );
                        ecs.deleteService(new DeleteServiceRequest()
                                .withCluster(cluster)
                                .withService(createService.getService().getServiceName())
                        );
                        sqs.deleteMessage(queue, message.getReceiptHandle());
                        if (request.getSnsArn() != null) {
                            sns.publish(
                                    new PublishRequest()
                                    .withSubject("Failed deployment of "+request.getServiceName())
                                    .withMessage("Failed to deploy version "+request.getBuildNumber()+" of "+request.getServiceName())
                                    .withTopicArn(request.getSnsArn())
                            );
                        }
                    } else {
                        //update service reference
                        if (db != null) {
                            HashMap<String, AttributeValue> keys = new HashMap<>();
                            keys.put(DYNAMO_HASH_KEY_NAME, new AttributeValue("services"));
                            keys.put(DYNAMO_RANGE_KEY_NAME, new AttributeValue(request.getServiceName() + "/current"));
                            Map<String, String> value = new HashMap<>();
                            value.put("version", "" + taskDefinitionRevision);
                            keys.put("value", new AttributeValue(gson.toJson(value)));
                            dynamo.putItem(db, keys);
                        }
                        sqs.deleteMessage(queue, message.getReceiptHandle());
                        Logger.getLogger(EcsServiceLauncher.class.getName()).log(Level.INFO, "successfully deployed update to " + request.getServiceName());
                        if (request.getSnsArn() != null) {
                            sns.publish(
                                    new PublishRequest()
                                    .withSubject("Successful deployment of "+request.getServiceName())
                                    .withMessage("Successfully deployed version "+request.getBuildNumber()+" of "+request.getServiceName())
                                    .withTopicArn(request.getSnsArn())
                            );
                        }
                        Thread.sleep(WAIT_TIME);
                        ListServicesResult listServices = ecs.listServices(
                                new ListServicesRequest()
                                .withCluster(cluster)
                        );
                        for (String arn : listServices.getServiceArns()) {
                            String[] split = arn.split(":");
                            String serviceName = split[split.length - 1];
                            if (!arn.equals(createService.getService().getServiceArn())
                                    && serviceName.startsWith("service/"+request.getServiceName() + "-")) {
                                ecs.updateService(new UpdateServiceRequest()
                                        .withCluster(cluster)
                                        .withDesiredCount(0)
                                        .withService(arn)
                                );
                                ecs.deleteService(new DeleteServiceRequest()
                                        .withCluster(cluster)
                                        .withService(arn)
                                );
                                Logger.getLogger(EcsServiceLauncher.class.getName()).log(Level.INFO, "successfully deleted old service " + arn);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private boolean healthCheck(String healthCheckUrl, EcsTaskTracker ecsTaskTracker, Service service, String family, int revision) {
        for (int i = 0; i < RETRIES; i++) {
            DescribeServicesResult describeServices = ecs.describeServices(
                    new DescribeServicesRequest()
                    .withCluster(service.getClusterArn())
                    .withServices(service.getServiceName())
            );
            Service latestService = describeServices.getServices().get(0);

            if (latestService.getDesiredCount() == latestService.getRunningCount()) {
                Map<String, List<Integer>> hostPortMapping = ecsTaskTracker.getHostPortMapping(family, "" + revision);
                return checkHealth(healthCheckUrl, hostPortMapping, RETRIES);
            }
            if (i < RETRIES - 1) {
                try {
                    Thread.sleep(WAIT_TIME);
                } catch (InterruptedException ex) {
                }
            }
        }
        return false;
    }

    private boolean checkHealth(String healthCheckPath, Map<String, List<Integer>> hostPortMapping, int remaining) {
        if (remaining >= 0) {
            boolean success = true;
            for (String host : hostPortMapping.keySet()) {
                for (int ip : hostPortMapping.get(host)) {
                    String url = "http://" + host + ":" + ip + healthCheckPath;
                    if (!this.checkHealth(url)) {
                        try {
                            Thread.sleep(WAIT_TIME);
                        } catch (InterruptedException ex) {
                        }
                        return checkHealth(healthCheckPath, hostPortMapping, remaining - 1);
                    }
                }
            }
            if (success) {
                return true;
            }
        }

        return false;
    }

    private boolean checkHealth(String url) {
        try {
            URL u = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) u.openConnection();
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                return true;
            }
        } catch (MalformedURLException ex) {
            Logger.getLogger(EcsServiceLauncher.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(EcsServiceLauncher.class.getName()).log(Level.SEVERE, null, ex);
        }
        return false;
    }
}
