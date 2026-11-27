package dev.shipyard.logging;

public record LogLine(long id, String deploymentId, String kind, String level, String line, String createdAt) {}
