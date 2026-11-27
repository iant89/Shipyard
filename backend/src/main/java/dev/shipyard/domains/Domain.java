package dev.shipyard.domains;

public record Domain(String id, String applicationId, String hostname, boolean tlsEnabled, boolean primary, String createdAt) {}
