package ca.omny.ecs.launcher;

import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ecs.AmazonECSClient;
import com.amazonaws.services.ecs.model.Container;
import com.amazonaws.services.ecs.model.ContainerInstance;
import com.amazonaws.services.ecs.model.DescribeContainerInstancesRequest;
import com.amazonaws.services.ecs.model.DescribeContainerInstancesResult;
import com.amazonaws.services.ecs.model.DescribeTaskDefinitionRequest;
import com.amazonaws.services.ecs.model.DescribeTaskDefinitionResult;
import com.amazonaws.services.ecs.model.DescribeTasksRequest;
import com.amazonaws.services.ecs.model.DescribeTasksResult;
import com.amazonaws.services.ecs.model.ListTasksRequest;
import com.amazonaws.services.ecs.model.ListTasksResult;
import com.amazonaws.services.ecs.model.NetworkBinding;
import com.amazonaws.services.ecs.model.Task;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import static java.util.concurrent.TimeUnit.SECONDS;

public class EcsTaskTracker {

    private AmazonECSClient ecsClient;
    private AmazonEC2Client ec2Client;

    Map<String, String> ec2InstanceIpMapping;
    Map<String, String> ec2IdToContainerArn;
    Set<String> knownContainers;

    Set<String> knownContainerInstances;
    Map<String, String> containerInstanceArnEc2Mapping;
    Map<String, String> taskToContainerInstanceMapping;
    Map<String, String> taskToTaskDefinitionMapping;

    Map<String, List<Integer>> taskPortMapping;

    Map<String, String> familyAndVersionToTaskDefinitionArnMap;
    Map<String, List<String>> familyToTaskArnCache;
    
    private final ScheduledExecutorService scheduler;
    private final boolean usePrivateIp;
    
    final Runnable updateFamilyArnCache = new Runnable() {
        public void run() { 
            for(String key: familyToTaskArnCache.keySet()) {
                String[] parts = key.split(":");
                familyToTaskArnCache.put(key, listTasks(parts[0], parts[1]));
            }
        }
    };
    
    public EcsTaskTracker(boolean usePrivateIp) {
        ecsClient = new AmazonECSClient();
        ec2Client = new AmazonEC2Client();
        familyAndVersionToTaskDefinitionArnMap = new HashMap<>();

        ec2InstanceIpMapping = new HashMap<>();
        ec2IdToContainerArn = new HashMap<>();
        knownContainers = new HashSet<>();
        knownContainerInstances = new HashSet<>();
        containerInstanceArnEc2Mapping = new HashMap<>();
        taskToContainerInstanceMapping = new HashMap<>();
        taskPortMapping = new HashMap<>();
        taskToTaskDefinitionMapping = new HashMap<>();
        familyToTaskArnCache = new HashMap<>();
        scheduler = Executors.newScheduledThreadPool(1);
        final ScheduledFuture<?> beeperHandle = scheduler.scheduleAtFixedRate(updateFamilyArnCache, 10, 20, SECONDS);
        this.usePrivateIp = usePrivateIp;
    }

    public Map<String, List<Integer>> getHostPortMapping(String family, String version) {
        Map<String, List<Integer>> hostPortMapping = new HashMap<>();
        String cluster = System.getenv("OMNY_ECS_CLUSTER");

        String taskDefinitionArn = getTaskDefinitionArn(family, version);

        List<String> taskArns = this.getTasks(cluster, family);
        System.out.println("using tasks " + taskArns);
        this.describeMissingTasks(taskArns, cluster, taskDefinitionArn);

        Set<String> containerInstanceArns = knownContainerInstances;
        List<String> unknownContainerArns = this.getUnknownContainerArns(containerInstanceArns);
        if (unknownContainerArns.size() > 0) {
            DescribeContainerInstancesResult describeContainerInstances = ecsClient.describeContainerInstances(new DescribeContainerInstancesRequest().withCluster(cluster).withContainerInstances(unknownContainerArns));
            List<ContainerInstance> containerInstances = describeContainerInstances.getContainerInstances();

            for (ContainerInstance instance : containerInstances) {
                ec2IdToContainerArn.put(instance.getEc2InstanceId(), instance.getContainerInstanceArn());
                containerInstanceArnEc2Mapping.put(instance.getContainerInstanceArn(), instance.getEc2InstanceId());
                knownContainers.add(instance.getContainerInstanceArn());
            }
        }
        List<String> instanceIds = this.getUnknownInstances(ec2IdToContainerArn);
        if (instanceIds.size() > 0) {
            DescribeInstancesResult describeInstances = ec2Client.describeInstances(new DescribeInstancesRequest().withInstanceIds(instanceIds));
            List<Reservation> reservations = describeInstances.getReservations();
            for (Reservation reservation : reservations) {
                for (Instance instance : reservation.getInstances()) {
                    String address = this.usePrivateIp? instance.getPrivateIpAddress() : instance.getPublicIpAddress();
                    ec2InstanceIpMapping.put(instance.getInstanceId(), address);
                }
            }
        }

        addInstancesToMapping(hostPortMapping, taskArns, taskDefinitionArn);
        return hostPortMapping;
    }
    
    private List<String> getTasks(String cluster, String family) {
        String key = cluster+":"+family;
        if(familyToTaskArnCache.containsKey(key)) {
            return familyToTaskArnCache.get(key);
        }
        List<String> listTasks = listTasks(cluster, family);
        familyToTaskArnCache.put(key, listTasks);
        return listTasks;
    }
    
    private List<String> listTasks(String cluster, String family) {
        ListTasksResult listTasks = ecsClient.listTasks(new ListTasksRequest()
                .withCluster(cluster)
                .withFamily(family));
        List<String> taskArns = listTasks.getTaskArns();
        return taskArns;
    }

    private Collection<String> getMissingTasks(List<String> taskArns) {
        LinkedList<String> missing = new LinkedList<>();
        for (String taskArn : taskArns) {
            if (!taskToContainerInstanceMapping.containsKey(taskArn)) {
                missing.add(taskArn);
            }
        }
        return missing;
    }

    private void describeMissingTasks(List<String> taskArns, String cluster, String taskDefinitionArn) {
        Collection<String> missingTasks = this.getMissingTasks(taskArns);
        if (missingTasks.isEmpty()) {
            return;
        }
        DescribeTasksRequest r = new DescribeTasksRequest()
                .withCluster(cluster)
                .withTasks(missingTasks);

        DescribeTasksResult tasksResult = ecsClient.describeTasks(r);

        for (Task task : tasksResult.getTasks()) {
            taskToTaskDefinitionMapping.put(task.getTaskArn(), task.getTaskDefinitionArn());
            taskToContainerInstanceMapping.put(task.getTaskArn(), task.getContainerInstanceArn());
            knownContainerInstances.add(task.getContainerInstanceArn());
            for (Container container : task.getContainers()) {
                for (NetworkBinding binding : container.getNetworkBindings()) {
                    if (binding.getContainerPort() == 8080 || binding.getContainerPort() == 80) {

                        int hostPort = binding.getHostPort();
                        String taskId = task.getTaskArn();
                        if (!taskPortMapping.containsKey(taskId)) {
                            taskPortMapping.put(taskId, new LinkedList<Integer>());
                        }

                        List<Integer> ports = taskPortMapping.get(taskId);
                        ports.add(hostPort);
                    }
                }
            }

        }
    }

    private List<String> getUnknownContainerArns(Collection<String> containerArns) {
        List<String> arns = new LinkedList<>();
        for (String arn : containerArns) {
            if (!knownContainers.contains(arn)) {
                arns.add(arn);
            }
        }
        return arns;
    }

    private List<String> getUnknownInstances(Map<String, String> ec2IdToContainerArn) {
        List<String> instanceIds = new LinkedList<>();
        for (String ec2Id : ec2IdToContainerArn.keySet()) {
            if (!ec2InstanceIpMapping.containsKey(ec2Id)) {
                instanceIds.add(ec2Id);
            }
        }
        return instanceIds;
    }

    private void addInstancesToMapping(Map<String, List<Integer>> hostPortMapping, Collection<String> tasksArns, String taskDefinitionArn) {
        System.out.println("registering tasks " + tasksArns);
        for (String taskArn : tasksArns) {
            String definitionArn = taskToTaskDefinitionMapping.get(taskArn);
            if(definitionArn.equals(taskDefinitionArn)) {
                String containerInstanceArn = taskToContainerInstanceMapping.get(taskArn);
                String ec2Id = containerInstanceArnEc2Mapping.get(containerInstanceArn);
                String privateIpAddress = ec2InstanceIpMapping.get(ec2Id);
                List<Integer> ports = taskPortMapping.get(taskArn);
                System.out.println(containerInstanceArn + " " + privateIpAddress + " " + ports);
                hostPortMapping.put(privateIpAddress, ports);
            } else {
                System.out.println("wrong task defintion for "+taskArn);
            }
        }
    }

    private String getTaskDefinitionArn(String family, String version) {
        String familyAndVersionKey = family + ":" + version;
        if (!familyAndVersionToTaskDefinitionArnMap.containsKey(family + ":" + version)) {
            DescribeTaskDefinitionResult describeTaskDefinition = ecsClient.describeTaskDefinition(new DescribeTaskDefinitionRequest().withTaskDefinition(familyAndVersionKey));
            familyAndVersionToTaskDefinitionArnMap.put(familyAndVersionKey, describeTaskDefinition.getTaskDefinition().getTaskDefinitionArn());
        }
        String taskDefinitionArn = familyAndVersionToTaskDefinitionArnMap.get(familyAndVersionKey);
        return taskDefinitionArn;
    }
}
