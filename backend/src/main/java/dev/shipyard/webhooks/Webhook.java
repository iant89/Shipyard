package dev.shipyard.webhooks;

public record Webhook(String id, String applicationId, String provider, String endpointId, String secretEnc,
                      boolean deployOnPush, String createdAt, String updatedAt) {}
