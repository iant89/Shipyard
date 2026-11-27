package dev.shipyard.audit;

public final class AuditActions {
    private AuditActions() {}

    public static final String USER_CREATED_APPLICATION = "USER_CREATED_APPLICATION";
    public static final String APPLICATION_UPDATED = "APPLICATION_UPDATED";
    public static final String APPLICATION_DELETED = "APPLICATION_DELETED";
    public static final String DEPLOYMENT_STARTED = "DEPLOYMENT_STARTED";
    public static final String DEPLOYMENT_COMPLETED = "DEPLOYMENT_COMPLETED";
    public static final String DEPLOYMENT_FAILED = "DEPLOYMENT_FAILED";
    public static final String DEPLOYMENT_ROLLED_BACK = "DEPLOYMENT_ROLLED_BACK";
    public static final String WEBHOOK_CREATED = "WEBHOOK_CREATED";
    public static final String WEBHOOK_UPDATED = "WEBHOOK_UPDATED";
    public static final String WEBHOOK_TRIGGERED = "WEBHOOK_TRIGGERED";
    public static final String ENVIRONMENT_VARIABLE_CHANGED = "ENVIRONMENT_VARIABLE_CHANGED";
    public static final String DOMAIN_CHANGED = "DOMAIN_CHANGED";
    public static final String USER_LOGGED_IN = "USER_LOGGED_IN";
}
