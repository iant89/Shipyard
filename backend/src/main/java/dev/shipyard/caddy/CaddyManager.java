package dev.shipyard.caddy;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.applications.ApplicationStatus;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.deployments.Deployment;
import dev.shipyard.deployments.DeploymentRepository;
import dev.shipyard.domains.Domain;
import dev.shipyard.domains.DomainRepository;
import dev.shipyard.storage.StorageLayout;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Generates the Caddy configuration from platform state and applies it
 * through Caddy's admin API. The generated Caddyfile is also written to
 * {@code caddy/Caddyfile} so Caddy starts with the latest routes after a restart.
 */
@Component
public class CaddyManager {
    private static final Logger log = LoggerFactory.getLogger(CaddyManager.class);

    public record ReloadResult(boolean applied, String message, String config) {}

    private final PlatformProperties props;
    private final StorageLayout layout;
    private final ApplicationRepository applications;
    private final DeploymentRepository deployments;
    private final DomainRepository domains;
    private final ExecutorService async = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("caddy-reload").factory());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .proxy(java.net.ProxySelector.of(null)).build();
    private volatile String lastStatus = "Not applied yet";

    public CaddyManager(PlatformProperties props, StorageLayout layout, @Lazy ApplicationRepository applications,
                        @Lazy DeploymentRepository deployments, @Lazy DomainRepository domains) {
        this.props = props;
        this.layout = layout;
        this.applications = applications;
        this.deployments = deployments;
        this.domains = domains;
    }

    public String lastStatus() {
        return lastStatus;
    }

    public void reloadAsync() {
        async.submit(() -> {
            try {
                reload();
            } catch (RuntimeException e) {
                log.warn("Caddy reload failed", e);
            }
        });
    }

    public synchronized ReloadResult reload() {
        String config = render();
        Path file = layout.caddyDir().resolve("Caddyfile");
        try {
            Files.writeString(file, config);
        } catch (IOException e) {
            log.warn("Could not write {}", file, e);
        }
        String admin = props.caddyAdminUrl();
        if (admin == null || admin.isBlank()) {
            lastStatus = "Caddy admin API not configured; Caddyfile written to " + file;
            return new ReloadResult(false, lastStatus, config);
        }
        try {
            byte[] body = config.getBytes(StandardCharsets.UTF_8);
            int status;
            String response;
            if (admin.startsWith("unix:")) {
                var r = UnixSocketHttp.post(Path.of(admin.substring("unix:".length()).replaceFirst("^//", "/")), "/load", "text/caddyfile", body);
                status = r.status();
                response = r.body();
            } else {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(admin.replaceAll("/$", "") + "/load"))
                        .timeout(Duration.ofSeconds(30)).header("Content-Type", "text/caddyfile")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
                status = r.statusCode();
                response = r.body();
            }
            if (status >= 200 && status < 300) {
                lastStatus = "Applied at " + java.time.Instant.now();
                return new ReloadResult(true, "Caddy configuration applied", config);
            }
            lastStatus = "Caddy rejected configuration (HTTP " + status + "): " + response;
            return new ReloadResult(false, lastStatus, config);
        } catch (IOException e) {
            lastStatus = "Could not reach Caddy admin API: " + e.getMessage();
            return new ReloadResult(false, lastStatus, config);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ReloadResult(false, "Interrupted", config);
        }
    }

    public String render() {
        boolean https = props.https();
        List<CaddyRoute> routes = new ArrayList<>();
        for (Application app : applications.findAll()) {
            List<CaddyRoute.Host> hosts = new ArrayList<>();
            for (Domain d : domains.findByApplication(app.id())) hosts.add(new CaddyRoute.Host(d.hostname(), d.tlsEnabled()));
            if (props.baseDomain() != null && !props.baseDomain().isBlank()) {
                String h = app.slug() + "." + props.baseDomain().strip();
                if (hosts.stream().noneMatch(x -> x.hostname().equals(h))) hosts.add(new CaddyRoute.Host(h, true));
            }
            if (hosts.isEmpty()) continue;
            Deployment current = app.currentDeploymentId() == null ? null : deployments.findById(app.currentDeploymentId()).orElse(null);
            if (current == null) {
                routes.add(new CaddyRoute(app.slug(), hosts, null, null, "Application has not been deployed yet"));
            } else if (app.status() == ApplicationStatus.STOPPED) {
                routes.add(new CaddyRoute(app.slug(), hosts, null, null, "Application is stopped"));
            } else if (current.isStatic()) {
                routes.add(new CaddyRoute(app.slug(), hosts, current.artifactPath(), null, null));
            } else {
                routes.add(new CaddyRoute(app.slug(), hosts, null, current.containerName() + ":" + current.internalPort(), null));
            }
        }
        return CaddyConfigGenerator.generate(adminDirective(), https, props.acmeEmail(), props.platformDomain(), props.platformUpstream(),
                routes);
    }

    /** The admin directive must keep Caddy listening where the platform will reach it next time. */
    private String adminDirective() {
        String admin = props.caddyAdminUrl();
        if (admin == null || admin.isBlank()) return null;
        if (admin.startsWith("unix:")) return "unix/" + admin.substring("unix:".length()).replaceFirst("^/+", "/");
        URI u = URI.create(admin);
        return "0.0.0.0:" + (u.getPort() > 0 ? u.getPort() : 2019);
    }
}
