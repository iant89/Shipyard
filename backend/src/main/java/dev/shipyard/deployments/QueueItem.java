package dev.shipyard.deployments;

public record QueueItem(String id, String applicationId, String deploymentId, String kind, String source, String commitSha,
                        String branch, int priority, String status, String createdAt, String startedAt, String finishedAt) {
    public static final String DEPLOY = "DEPLOY";
    public static final String ROLLBACK = "ROLLBACK";
}
