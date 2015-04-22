package ca.omny.ecs.launcher;

public class Main {
    public static void main(String[] args) {
        EcsServiceLauncher serviceLauncher = new EcsServiceLauncher();
        String queue = System.getenv("ECS_LAUNCHER_QUEUE");
        String cluster = System.getenv("ECS_LAUNCHER_CLUSTER");
        String bucket = System.getenv("ECS_LAUNCHER_BUCKET");
        String db = System.getenv("ECS_LAUNCHER_DB");
        boolean usePrivateIpAddress = !(System.getenv("ECS_LAUNCHER_USE_PUBLIC_IP") != null
                && Boolean.parseBoolean(System.getenv("ECS_LAUNCHER_USE_PUBLIC_IP")));
        serviceLauncher.run(queue, cluster, bucket, db, usePrivateIpAddress);
    }
}
