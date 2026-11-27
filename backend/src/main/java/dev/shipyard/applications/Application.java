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
        String createdAt,
        String updatedAt) {
}
