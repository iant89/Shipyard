package dev.shipyard.events;

/** Names of events streamed to clients (see spec sections 57 and 68). */
public final class EventTypes {
    private EventTypes() {}

    public static final String WEBHOOK_RECEIVED = "WebhookReceived";
    public static final String WEBHOOK_PROCESSED = "WebhookProcessed";
    public static final String DEPLOYMENT_REQUESTED = "DeploymentRequested";
    public static final String DEPLOYMENT_STATUS = "DeploymentStatusChanged";
    public static final String BUILD_STARTED = "BuildStarted";
    public static final String BUILD_LOG = "BuildLog";
    public static final String BUILD_COMPLETED = "BuildCompleted";
    public static final String DEPLOYMENT_STARTED = "DeploymentStarted";
    public static final String DEPLOYMENT_LOG = "DeploymentLog";
    public static final String DEPLOYMENT_HEALTHY = "DeploymentHealthy";
    public static final String DEPLOYMENT_FAILED = "DeploymentFailed";
    public static final String DEPLOYMENT_ROLLED_BACK = "DeploymentRolledBack";
    public static final String APPLICATION_STARTED = "ApplicationStarted";
    public static final String APPLICATION_STOPPED = "ApplicationStopped";
    public static final String APPLICATION_CRASHED = "ApplicationCrashed";
    public static final String APPLICATION_UPDATED = "ApplicationUpdated";
    public static final String METRICS_UPDATED = "MetricsUpdated";
}
