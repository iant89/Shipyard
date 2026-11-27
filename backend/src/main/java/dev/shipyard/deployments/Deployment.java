package dev.shipyard.deployments;

public record Deployment(
        String id,
        String applicationId,
        String serviceId,
        int number,
        String commitSha,
        String commitMessage,
        String commitAuthor,
        String branch,
        DeploymentSource source,
        DeploymentStatus status,
        String buildStatus,
        String runtimeStatus,
        String projectType,
        String framework,
        String imageTag,
        String artifactPath,
        String containerName,
        Integer internalPort,
        String rollbackOf,
        String triggeredBy,
        String createdAt,
        String startedAt,
        String finishedAt,
        String error) {

    public boolean isStatic() {
        return "STATIC".equals(projectType);
    }
}
