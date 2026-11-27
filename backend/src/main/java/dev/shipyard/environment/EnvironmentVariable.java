package dev.shipyard.environment;

public record EnvironmentVariable(String id, String applicationId, String name, String valueEnc, boolean secret,
                                  String createdAt, String updatedAt) {}
