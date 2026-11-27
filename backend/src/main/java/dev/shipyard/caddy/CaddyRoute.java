package dev.shipyard.caddy;

import java.util.List;

/**
 * One site block. Exactly one of {@code staticRoot}, {@code upstream} or
 * {@code unavailableMessage} is set.
 */
public record CaddyRoute(String applicationSlug, List<Host> hosts, String staticRoot, String upstream, String unavailableMessage) {
    public record Host(String hostname, boolean tls) {}
}
