package dev.shipyard.runtime;

/** Point-in-time resource usage of a container. */
public record ContainerStats(String name, double cpuPercent, long memoryBytes, long memoryLimitBytes,
                             long networkRxBytes, long networkTxBytes, long blockReadBytes, long blockWriteBytes, int pids) {
}
