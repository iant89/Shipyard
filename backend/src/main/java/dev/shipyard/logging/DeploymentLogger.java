package dev.shipyard.logging;

import dev.shipyard.security.SecretMasker;

public final class DeploymentLogger {
    private final LogService logs;
    private final String applicationId;
    private final String deploymentId;
    private final SecretMasker masker;

    DeploymentLogger(LogService logs, String applicationId, String deploymentId, SecretMasker masker) {
        this.logs = logs;
        this.applicationId = applicationId;
        this.deploymentId = deploymentId;
        this.masker = masker;
    }

    public void info(String line) {
        logs.deployment(applicationId, deploymentId, "INFO", line, masker);
    }

    public void warn(String line) {
        logs.deployment(applicationId, deploymentId, "WARN", line, masker);
    }

    public void error(String line) {
        logs.deployment(applicationId, deploymentId, "ERROR", line, masker);
    }

    public void build(String line) {
        logs.build(applicationId, deploymentId, line, masker);
    }

    public SecretMasker masker() {
        return masker;
    }
}
