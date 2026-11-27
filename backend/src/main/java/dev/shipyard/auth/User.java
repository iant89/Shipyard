package dev.shipyard.auth;

public record User(String id, String username, String passwordHash, String role, String createdAt) {}
