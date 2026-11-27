package dev.shipyard.metrics;

/** One resource sample. Network values are bytes per second since the previous sample. */
public record MetricSample(String timestamp, double cpuPercent, long memoryBytes, long memoryLimitBytes,
                           double networkRxRate, double networkTxRate, long diskUsedBytes, long diskTotalBytes) {}
