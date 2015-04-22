package ca.omny.ecs.launcher;

public class ServiceUpdateRequest {
    String site;
    String serviceName;
    int buildNumber;
    boolean replaceLastIfExists;
    String healthCheckPath;
    int numToRun;

    public String getSite() {
        return site;
    }

    public void setSite(String site) {
        this.site = site;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public int getBuildNumber() {
        return buildNumber;
    }

    public void setBuildNumber(int buildNumber) {
        this.buildNumber = buildNumber;
    }

    public boolean isReplaceLastIfExists() {
        return replaceLastIfExists;
    }

    public void setReplaceLastIfExists(boolean replaceLastIfExists) {
        this.replaceLastIfExists = replaceLastIfExists;
    }

    public String getHealthCheckPath() {
        return healthCheckPath;
    }

    public void setHealthCheckPath(String healthCheckPath) {
        this.healthCheckPath = healthCheckPath;
    }

    public int getNumToRun() {
        return numToRun;
    }

    public void setNumToRun(int numToRun) {
        this.numToRun = numToRun;
    }
}
