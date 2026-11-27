package dev.shipyard.detection;

public enum ProjectType {
    /** Produces files served directly by Caddy. */
    STATIC,
    /** Starts a process that listens on a port, run as a container. */
    SERVER
}
