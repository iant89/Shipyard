package dev.shipyard.runtime;

/** Subset of container state the platform cares about. */
public record ContainerInfo(String id, String name, String image, String status, boolean running, int restartCount,
                            int exitCode, String startedAt, String finishedAt, String health, String ipAddress) {
}
