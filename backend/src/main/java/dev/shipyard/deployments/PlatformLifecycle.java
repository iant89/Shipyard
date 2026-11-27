package dev.shipyard.deployments;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.caddy.CaddyManager;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.runtime.ContainerRuntime;
import dev.shipyard.storage.StorageLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Startup reconciliation and periodic cleanup (CleanupWorker). */
@Component
public class PlatformLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PlatformLifecycle.class);

    private final ContainerRuntime runtime;
    private final CaddyManager caddy;
    private final ApplicationRepository applications;
    private final DeploymentRepository deployments;
    private final StorageLayout layout;
    private final PlatformProperties props;

    public PlatformLifecycle(ContainerRuntime runtime, CaddyManager caddy, ApplicationRepository applications,
                             DeploymentRepository deployments, StorageLayout layout, PlatformProperties props) {
        this.runtime = runtime;
        this.caddy = caddy;
        this.applications = applications;
        this.deployments = deployments;
        this.layout = layout;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        if (runtime.available()) {
            try {
                runtime.ensureNetwork(props.dockerNetwork());
            } catch (RuntimeException e) {
                log.warn("Could not ensure docker network {}: {}", props.dockerNetwork(), e.getMessage());
            }
        } else {
            log.warn("Docker is not reachable; builds and server applications will fail until it is");
        }
        caddy.reloadAsync();
    }

    /**
     * Keeps the newest {@code retain-deployments} artifacts per application
     * (plus the current one) and removes older images and static artifacts.
     */
    @Scheduled(cron = "0 17 3 * * *")
    public void cleanup() {
        int retain = props.retainDeployments();
        for (Application app : applications.findAll()) {
            List<Deployment> all = deployments.findByApplication(app.id(), 10_000);
            Set<String> keep = all.stream().limit(retain).map(Deployment::id).collect(Collectors.toSet());
            if (app.currentDeploymentId() != null) keep.add(app.currentDeploymentId());
            for (Deployment d : all) {
                if (keep.contains(d.id()) || !d.status().isTerminal()) continue;
                if (d.imageTag() != null) runtime.removeImage(d.imageTag());
                StorageLayout.deleteRecursively(layout.deploymentDir(app.id(), d.number()));
                deployments.setArtifact(d.id(), null, null);
            }
        }
        // Stale build directories from crashed builds
        Path builds = layout.root().resolve("builds");
        try (var s = Files.list(builds)) {
            Instant cutoff = Instant.now().minus(Duration.ofDays(1));
            s.filter(p -> p.getFileName().toString().startsWith("detect-")).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) StorageLayout.deleteRecursively(p);
                } catch (java.io.IOException ignored) { }
            });
        } catch (java.io.IOException ignored) { }
        log.info("Cleanup finished");
    }
}
