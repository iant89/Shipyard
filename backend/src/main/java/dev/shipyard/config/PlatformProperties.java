package dev.shipyard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform configuration, bound from {@code shipyard.*} properties
 * (or SHIPYARD_* environment variables).
 */
@ConfigurationProperties(prefix = "shipyard")
public record PlatformProperties(
        /** Root of all platform state: repositories, deployments, logs, database. */
        String dataDir,
        /** Public base URL of the platform, used to render webhook URLs. */
        String publicUrl,
        /** Hostname of the platform UI itself, routed by Caddy (optional). */
        String platformDomain,
        /** When set, every application is also served at {slug}.{baseDomain}. */
        String baseDomain,
        /** Docker network shared by Caddy, the platform and hosted applications. */
        String dockerNetwork,
        /** Path of the docker CLI binary. */
        String dockerBinary,
        /** Path of the git CLI binary. */
        String gitBinary,
        /** Caddy admin API, e.g. http://caddy:2019. Empty disables live reload. */
        String caddyAdminUrl,
        /** When false, Caddy serves plain HTTP only: no ACME, no certificates, no 443. */
        boolean https,
        /** Email used by Caddy for ACME registration (optional). */
        String acmeEmail,
        /** Upstream Caddy should use to reach the platform UI. */
        String platformUpstream,
        /** Number of applications that may deploy concurrently. */
        int workerThreads,
        /** Deployments to keep per application (artifacts and images). */
        int retainDeployments,
        /** Consecutive restarts after which an application is CRASHED. */
        int crashRestartThreshold,
        /** Allow file:// repositories (local Git). Off by default. */
        boolean allowLocalRepositories,
        /** Initial admin credentials for first boot (optional). */
        String initialAdminUsername,
        String initialAdminPassword,
        /** Shipyard's own git checkout (mounted read-only by docker compose), used for self-update. */
        String sourceDir,
        /** Host port Caddy's HTTP listener is published on, for local hostnames' URLs. Detected from the Caddy container when unset. */
        Integer httpPort,
        /** Publish the .local hostnames of running applications with mDNS (the compose mdns service answers). */
        boolean mdns,
        /** Subnet for the application network when Shipyard creates it (e.g. 172.30.0.0/20); blank lets Docker choose. */
        String dockerSubnet
) {
    public PlatformProperties {
        if (dataDir == null || dataDir.isBlank()) dataDir = "/var/lib/shipyard";
        if (publicUrl == null || publicUrl.isBlank()) publicUrl = "http://localhost:8080";
        if (dockerNetwork == null || dockerNetwork.isBlank()) dockerNetwork = "shipyard";
        if (dockerBinary == null || dockerBinary.isBlank()) dockerBinary = "docker";
        if (gitBinary == null || gitBinary.isBlank()) gitBinary = "git";
        if (caddyAdminUrl == null) caddyAdminUrl = "";
        if (platformDomain != null && platformDomain.strip().equalsIgnoreCase("shipyard.example.com")) platformDomain = "";
        if (!https) acmeEmail = "";
        if (platformUpstream == null || platformUpstream.isBlank()) platformUpstream = "shipyard:8080";
        if (workerThreads <= 0) workerThreads = 2;
        if (retainDeployments <= 0) retainDeployments = 10;
        if (crashRestartThreshold <= 0) crashRestartThreshold = 5;
        if (sourceDir == null || sourceDir.isBlank()) sourceDir = "/opt/shipyard/source";
    }

    public String publicUrlNoSlash() {
        return publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }
}
