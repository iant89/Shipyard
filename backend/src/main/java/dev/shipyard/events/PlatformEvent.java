package dev.shipyard.events;

import java.util.Map;

/**
 * Event published on the internal event bus and streamed to the UI over SSE.
 *
 * @param type           e.g. DeploymentStarted, BuildLog, MetricsUpdated
 * @param applicationId  owning application, or null for platform-wide events
 * @param deploymentId   related deployment, if any
 * @param data           event payload (JSON-serialisable)
 */
public record PlatformEvent(String type, String applicationId, String deploymentId, Map<String, Object> data, String timestamp) {
}
