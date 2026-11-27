package dev.shipyard.applications;

/**
 * Build and runtime configuration of one application service. MVP
 * applications have exactly one service named {@code web}.
 * Null build fields mean "use detection".
 */
public record ServiceConfig(
        String id,
        String applicationId,
        String name,
        String projectType,
        String framework,
        String buildStrategy,
        String rootDirectory,
        String installCommand,
        String buildCommand,
        String testCommand,
        String startCommand,
        String outputDirectory,
        String packageManager,
        String runtimeImage,
        Integer internalPort,
        String healthCheckType,
        String healthCheckPath,
        int healthCheckTimeoutSeconds,
        double cpuLimit,
        int memoryLimitMb,
        String restartPolicy,
        int restartCount,
        String createdAt,
        String updatedAt) {

    public static final String DEFAULT_SERVICE = "web";
}
