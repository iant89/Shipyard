package dev.shipyard.webhooks;

public record WebhookEvent(String id, String applicationId, String provider, String eventType, String deliveryId, String repository,
                           String branch, String commitSha, String previousSha, String author, String commitMessage, String receivedAt,
                           String status, String deploymentId, String error, boolean test) {}
