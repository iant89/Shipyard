package dev.shipyard.applications;

public record Application(
        String id,
        String slug,
        String name,
        String description,
        ApplicationStatus status,
        String currentDeploymentId,
        boolean autoDeploy,
        boolean autoRollback,
        boolean cancelStaleBuilds,
        int buildConcurrency,
        /** Poll the branch every 90 seconds and deploy new commits (see CommitPoller). */
        boolean autoUpdate,
        String createdAt,
        String updatedAt) {
}
