package dev.shipyard.webhooks;

/** Provider-neutral view of an incoming webhook. */
public record ParsedEvent(String eventType, String deliveryId, String repository, String branch, String commitSha,
                          String previousSha, String author, String commitMessage, String timestamp, boolean branchDeleted) {}
