package dev.shipyard.caddy;

import java.util.List;

/**
 * One site block. It serves a static root, proxies to an upstream, responds with
 * an unavailable message, or displays the maintenance page.
 */
public record CaddyRoute(String applicationSlug, List<Host> hosts, String staticRoot, String upstream, String unavailableMessage,
                         boolean maintenance) {
    public CaddyRoute(String applicationSlug, List<Host> hosts, String staticRoot, String upstream, String unavailableMessage) {
        this(applicationSlug, hosts, staticRoot, upstream, unavailableMessage, false);
    }

    public static CaddyRoute maintenance(String applicationSlug, List<Host> hosts) {
        return new CaddyRoute(applicationSlug, hosts, null, null, null, true);
    }

    public record Host(String hostname, boolean tls) {}
}
