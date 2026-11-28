package dev.shipyard.deployments;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.applications.ApplicationStatus;
import dev.shipyard.applications.ServiceConfig;
import dev.shipyard.applications.ServiceRepository;
import dev.shipyard.applications.Volumes;
import dev.shipyard.audit.AuditActions;
import dev.shipyard.audit.AuditService;
import dev.shipyard.builds.BuildArtifact;
import dev.shipyard.builds.BuildEngine;
import dev.shipyard.caddy.CaddyManager;
import dev.shipyard.common.CancellationToken;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.detection.DetectionResult;
import dev.shipyard.detection.ProjectDetector;
import dev.shipyard.detection.ProjectType;
import dev.shipyard.environment.EnvironmentService;
import dev.shipyard.events.EventBus;
import dev.shipyard.events.EventTypes;
import dev.shipyard.logging.DeploymentLogger;
import dev.shipyard.logging.LogService;
import dev.shipyard.repositories.GitRepository;
import dev.shipyard.repositories.GitRepositoryStore;
import dev.shipyard.repositories.RepositoryManager;
import dev.shipyard.runtime.ContainerRuntime;
import dev.shipyard.runtime.ContainerSpec;
import dev.shipyard.storage.StorageLayout;
import dev.shipyard.webhooks.WebhookEventStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes one queue item. The only code path that changes what is running:
 *
 * <pre>
 * CLONING → DETECTING → BUILDING → TESTING → DEPLOYING → STARTING → HEALTH_CHECK → RUNNING
 *                                                                        └→ FAILED (previous stays online)
 * </pre>
 *
 * A new deployment only replaces the current one after it has passed its
 * health check, so a failed deployment never takes a healthy one offline.
 */
@Component
public class DeploymentPipeline {
    private static final Logger log = LoggerFactory.getLogger(DeploymentPipeline.class);

    private final DeploymentRepository deployments;
    private final ApplicationRepository applications;
    private final ServiceRepository services;
    private final GitRepositoryStore repositories;
    private final RepositoryManager repoManager;
    private final ProjectDetector detector;
    private final BuildEngine builds;
    private final ContainerRuntime runtime;
    private final HealthChecker health;
    private final CaddyManager caddy;
    private final EnvironmentService environment;
    private final LogService logs;
    private final EventBus events;
    private final AuditService audit;
    private final StorageLayout layout;
    private final PlatformProperties props;
    private final WebhookEventStore webhookEvents;

    public DeploymentPipeline(DeploymentRepository deployments, ApplicationRepository applications, ServiceRepository services,
                              GitRepositoryStore repositories, RepositoryManager repoManager, ProjectDetector detector,
                              BuildEngine builds, ContainerRuntime runtime, HealthChecker health, CaddyManager caddy,
                              EnvironmentService environment, LogService logs, EventBus events, AuditService audit,
                              StorageLayout layout, PlatformProperties props, WebhookEventStore webhookEvents) {
        this.deployments = deployments;
        this.applications = applications;
        this.services = services;
        this.repositories = repositories;
        this.repoManager = repoManager;
        this.detector = detector;
        this.builds = builds;
        this.runtime = runtime;
        this.health = health;
        this.caddy = caddy;
        this.environment = environment;
        this.logs = logs;
        this.events = events;
        this.audit = audit;
        this.layout = layout;
        this.props = props;
        this.webhookEvents = webhookEvents;
    }

    /** Runs the item; returns the final queue status. */
    public String execute(QueueItem item, CancellationToken cancel) {
        return QueueItem.ROLLBACK.equals(item.kind()) ? rollback(item, cancel) : deploy(item, cancel);
    }

    // ------------------------------------------------------------------ deploy

    private String deploy(QueueItem item, CancellationToken cancel) {
        Deployment d = deployments.get(item.deploymentId());
        Application app = applications.get(d.applicationId());
        ServiceConfig service = services.get(d.serviceId());
        DeploymentLogger out = logs.forDeployment(app.id(), d.id(), environment.masker(app.id()));
        Path workDir = layout.buildDir(app.id(), d.number());
        Path deploymentDir = layout.deploymentDir(app.id(), d.number());
        String newContainer = null;
        ApplicationStatus statusBefore = app.status();
        webhookEvents.updateByDeployment(d.id(), "PROCESSING", null);
        deployments.markStarted(d.id());
        events.publish(EventTypes.DEPLOYMENT_STARTED, app.id(), d.id(), Map.of("number", d.number()));
        out.info("Deployment #" + d.number() + " of " + app.name() + " started (source " + d.source() + ")");
        try {
            if (app.currentDeploymentId() == null) applications.updateStatus(app.id(), ApplicationStatus.BUILDING);
            else applications.updateStatus(app.id(), ApplicationStatus.DEPLOYING);
            appChanged(app.id());
            reloadRoutes(out, "Application URL is showing the update screen");

            // CLONING — fetch and pin the exact commit
            transition(d, DeploymentStatus.CLONING);
            GitRepository repo = repositories.forApplication(app.id());
            repoManager.cloneOrFetch(repo, out::build, cancel);
            String sha = repoManager.resolveCommit(repo, d.commitSha() != null ? d.commitSha() : d.branch(), out::build, cancel);
            var commit = repoManager.getCommit(app.id(), sha);
            deployments.setCommit(d.id(), sha, commit.message(), commit.author());
            out.info("Checking out commit " + sha.substring(0, 12) + " — " + commit.message());
            Path source = workDir.resolve("src");
            repoManager.checkoutCommit(app.id(), sha, source, cancel);
            cancel.throwIfCancelled();

            // DETECTING
            transition(d, DeploymentStatus.DETECTING);
            DetectionResult detection = detector.detect(source, service);
            Path buildRoot = service.rootDirectory() == null || service.rootDirectory().isBlank() || service.rootDirectory().equals(".")
                    ? source : source.resolve(service.rootDirectory()).normalize();
            out.info("Detected " + detection.projectType() + " application: " + nz(detection.framework()) + " via " + detection.source()
                    + (detection.port() != null && detection.projectType() == ProjectType.SERVER ? ", port " + detection.port() : ""));
            detection.notes().forEach(n -> out.info("  note: " + n));
            if (detection.buildStrategy() == null) throw new IllegalStateException(String.join(" ", detection.notes()));
            deployments.setDetection(d.id(), detection.projectType().name(), detection.framework(), detection.port());

            // BUILDING (+ TESTING inside the build container)
            transition(d, DeploymentStatus.BUILDING);
            deployments.setBuildStatus(d.id(), "RUNNING");
            events.publish(EventTypes.BUILD_STARTED, app.id(), d.id(), Map.of("number", d.number()));
            Map<String, String> labels = labels(app, d);
            BuildArtifact artifact = builds.build(new BuildEngine.BuildContext(app.slug(), d.number(), buildRoot, workDir, deploymentDir,
                    detection, environment.buildEnvironment(app.id()), labels, service.cpuLimit(), service.memoryLimitMb()), out::build, cancel);
            if (detection.testCommand() != null) {
                transition(d, DeploymentStatus.TESTING);
                out.info("Tests passed (" + detection.testCommand() + ")");
            }
            deployments.setBuildStatus(d.id(), "SUCCEEDED");
            events.publish(EventTypes.BUILD_COMPLETED, app.id(), d.id(), Map.of("number", d.number(), "success", true));
            cancel.throwIfCancelled();

            // DEPLOYING / STARTING / HEALTH_CHECK
            transition(d, DeploymentStatus.DEPLOYING);
            if (artifact.kind() == BuildArtifact.Kind.STATIC) {
                deployments.setArtifact(d.id(), null, artifact.staticDir().toString());
                transition(d, DeploymentStatus.HEALTH_CHECK);
                if (!health.checkStatic(artifact.staticDir(), out::info)) throw new IllegalStateException("Static artifact check failed");
            } else {
                deployments.setArtifact(d.id(), artifact.imageTag(), null);
                newContainer = containerName(app, service, d.number());
                startContainer(app, service, d, artifact.imageTag(), newContainer, detection.port(), labels, out);
                transition(d, DeploymentStatus.HEALTH_CHECK);
                int port = detection.port() == null ? 8080 : detection.port();
                boolean healthy = health.check(new HealthChecker.Target(newContainer, port,
                        detection.healthCheckType(), detection.healthCheckPath(), service.healthCheckTimeoutSeconds()), out::info, cancel);
                if (!healthy) {
                    runtime.logs(newContainer, 40).forEach(l -> out.build("[app] " + l));
                    throw new IllegalStateException("Health check failed");
                }
            }

            // Switch traffic, then retire the previous deployment.
            activate(app, d, out);
            newContainer = null; // now owned by the running deployment
            out.info("Deployment #" + d.number() + " is RUNNING");
            audit.record(AuditActions.DEPLOYMENT_COMPLETED, "DEPLOYMENT", d.id(), Map.of("application", app.slug(), "number", d.number()));
            webhookEvents.updateByDeployment(d.id(), "DEPLOYED", null);
            events.publish(EventTypes.DEPLOYMENT_HEALTHY, app.id(), d.id(), Map.of("number", d.number()));
            return "COMPLETED";
        } catch (CancellationToken.CancelledException e) {
            out.warn("Deployment cancelled");
            fail(app, d, DeploymentStatus.CANCELLED, "Cancelled", newContainer, statusBefore, out);
            return "CANCELLED";
        } catch (RuntimeException e) {
            log.warn("Deployment {} of {} failed: {}", d.number(), app.slug(), e.getMessage());
            out.error("Deployment failed: " + e.getMessage());
            fail(app, d, DeploymentStatus.FAILED, e.getMessage(), newContainer, statusBefore, out);
            return "FAILED";
        } finally {
            StorageLayout.deleteRecursively(workDir);
        }
    }

    // ------------------------------------------------------------------ rollback

    private String rollback(QueueItem item, CancellationToken cancel) {
        Deployment target = deployments.get(item.deploymentId());
        Application app = applications.get(target.applicationId());
        ServiceConfig service = services.get(target.serviceId());
        DeploymentLogger out = logs.forDeployment(app.id(), target.id(), environment.masker(app.id()));
        ApplicationStatus statusBefore = app.status();
        String newContainer = null;
        out.info("Rolling back to deployment #" + target.number());
        applications.updateStatus(app.id(), ApplicationStatus.ROLLING_BACK);
        appChanged(app.id());
        reloadRoutes(out, "Application URL is showing the update screen");
        try {
            if (target.isStatic()) {
                if (target.artifactPath() == null || !Files.isDirectory(Path.of(target.artifactPath()))) {
                    throw new IllegalStateException("Artifact of deployment #" + target.number() + " is no longer available");
                }
                transition(target, DeploymentStatus.HEALTH_CHECK);
                if (!health.checkStatic(Path.of(target.artifactPath()), out::info)) throw new IllegalStateException("Static artifact check failed");
            } else {
                // A fresh container from the immutable image; the name carries a suffix so it never collides.
                newContainer = containerName(app, service, target.number()) + "-r" + (System.currentTimeMillis() / 1000);
                transition(target, DeploymentStatus.STARTING);
                startContainer(app, service, target, target.imageTag(), newContainer, target.internalPort(), labels(app, target), out);
                transition(target, DeploymentStatus.HEALTH_CHECK);
                boolean healthy = health.check(new HealthChecker.Target(newContainer, target.internalPort() == null ? 8080 : target.internalPort(),
                        service.healthCheckType(), service.healthCheckPath(), service.healthCheckTimeoutSeconds()), out::info, cancel);
                if (!healthy) throw new IllegalStateException("Health check failed");
                deployments.setContainer(target.id(), newContainer);
            }
            String previousId = app.currentDeploymentId();
            activate(app, deployments.get(target.id()), out);
            newContainer = null;
            if (previousId != null && !previousId.equals(target.id())) {
                deployments.updateStatus(previousId, DeploymentStatus.ROLLED_BACK);
                events.publish(EventTypes.DEPLOYMENT_STATUS, app.id(), previousId, Map.of("status", DeploymentStatus.ROLLED_BACK.name()));
            }
            audit.record(AuditActions.DEPLOYMENT_ROLLED_BACK, "DEPLOYMENT", target.id(), Map.of("application", app.slug(), "number", target.number()));
            events.publish(EventTypes.DEPLOYMENT_ROLLED_BACK, app.id(), target.id(), Map.of("number", target.number()));
            out.info("Rollback complete: deployment #" + target.number() + " is RUNNING");
            return "COMPLETED";
        } catch (RuntimeException e) {
            out.error("Rollback failed: " + e.getMessage());
            if (newContainer != null) runtime.remove(newContainer);
            // The target keeps its previous terminal status; the current deployment is untouched.
            deployments.updateStatus(target.id(), target.status());
            applications.updateStatus(app.id(), restoredStatus(app.id(), statusBefore));
            reloadRoutes(out, "Previous application state restored in Caddy");
            appChanged(app.id());
            return e instanceof CancellationToken.CancelledException ? "CANCELLED" : "FAILED";
        }
    }

    // ------------------------------------------------------------------ steps

    private void startContainer(Application app, ServiceConfig service, Deployment d, String image, String name, Integer port,
                                Map<String, String> labels, DeploymentLogger out) {
        transition(d, DeploymentStatus.STARTING);
        runtime.ensureNetwork(props.dockerNetwork());
        runtime.remove(name); // leftovers from an interrupted attempt
        Map<String, String> env = new LinkedHashMap<>(environment.runtimeEnvironment(app.id()));
        if (port != null) env.putIfAbsent("PORT", String.valueOf(port));
        List<String> mounts = new ArrayList<>();
        for (String path : service.volumes()) {
            String volume = Volumes.volumeName(app.slug(), path);
            runtime.ensureVolume(volume, Map.of(Volumes.LABEL_APPLICATION, app.id(), "dev.shipyard.managed", "true", "dev.shipyard.path", path));
            mounts.add(volume + ":" + path);
            out.info("Volume " + volume + " mounted at " + path);
        }
        runtime.create(new ContainerSpec(name, image, env, null, port, service.cpuLimit(), service.memoryLimitMb(),
                service.restartPolicy(), props.dockerNetwork(), List.of(), mounts, labels));
        deployments.setContainer(d.id(), name);
        runtime.start(name);
        out.info("Started container " + name + " (" + service.cpuLimit() + " CPU, " + service.memoryLimitMb() + " MB)");
        deployments.setRuntimeStatus(d.id(), "STARTING");
    }

    /** Points traffic at {@code d} and retires the previously current deployment. */
    private void activate(Application app, Deployment stale, DeploymentLogger out) {
        Deployment d = deployments.get(stale.id());
        Application fresh = applications.get(app.id());
        String previousId = fresh.currentDeploymentId();
        Deployment previous = previousId == null || previousId.equals(d.id()) ? null : deployments.findById(previousId).orElse(null);

        layout.pointCurrent(app.id(), d.number());
        applications.setCurrentDeployment(app.id(), d.id());
        applications.updateStatus(app.id(), ApplicationStatus.RUNNING);
        deployments.finish(d.id(), DeploymentStatus.RUNNING, null);
        deployments.setRuntimeStatus(d.id(), "RUNNING");
        services.setRestartCount(d.serviceId(), 0);
        var reload = caddy.reload();
        out.info(reload.applied() ? "Traffic switched to #" + d.number() + " (Caddy updated)" : "Caddy: " + reload.message());

        if (previous != null) {
            if (!previous.isStatic() && previous.containerName() != null && !previous.containerName().equals(d.containerName())) {
                runtime.stop(previous.containerName(), 20);
                runtime.remove(previous.containerName());
            }
            if (previous.status() == DeploymentStatus.RUNNING || previous.status() == DeploymentStatus.STOPPED) {
                deployments.updateStatus(previous.id(), DeploymentStatus.STOPPED);
            }
            deployments.setRuntimeStatus(previous.id(), "STOPPED");
            out.info("Previous deployment #" + previous.number() + " stopped (artifact kept for rollback)");
            events.publish(EventTypes.DEPLOYMENT_STATUS, app.id(), previous.id(), Map.of("status", "STOPPED"));
        }
        events.publish(EventTypes.APPLICATION_STARTED, app.id(), d.id(), Map.of("number", d.number()));
        appChanged(app.id());
    }

    private void fail(Application app, Deployment d, DeploymentStatus status, String error, String newContainer,
                      ApplicationStatus statusBefore, DeploymentLogger out) {
        Deployment latest = deployments.get(d.id());
        if ("RUNNING".equals(latest.buildStatus()) || "PENDING".equals(latest.buildStatus())) {
            deployments.setBuildStatus(d.id(), status == DeploymentStatus.CANCELLED ? "CANCELLED" : "FAILED");
            events.publish(EventTypes.BUILD_COMPLETED, app.id(), d.id(), Map.of("number", d.number(), "success", false));
        }
        if (newContainer != null) {
            if (app.autoRollback()) {
                runtime.remove(newContainer);
                out.info("Removed failed container " + newContainer);
            } else {
                runtime.stop(newContainer, 5);
                out.info("Automatic rollback is off: failed container " + newContainer + " kept (stopped) for inspection");
            }
        }
        deployments.setRuntimeStatus(d.id(), "STOPPED");
        deployments.finish(d.id(), status, error);
        ApplicationStatus restored = restoredStatus(app.id(), statusBefore);
        applications.updateStatus(app.id(), restored);
        Application fresh = applications.get(app.id());
        if (fresh.currentDeploymentId() != null) {
            deployments.findById(fresh.currentDeploymentId()).ifPresent(cur ->
                    out.info("Previous deployment #" + cur.number() + " remains online"));
        }
        if (status == DeploymentStatus.FAILED) {
            audit.record(AuditActions.DEPLOYMENT_FAILED, "DEPLOYMENT", d.id(), Map.of("application", app.slug(), "number", d.number(),
                    "error", error == null ? "" : error));
            events.publish(EventTypes.DEPLOYMENT_FAILED, app.id(), d.id(), Map.of("number", d.number(), "error", error == null ? "" : error));
        }
        events.publish(EventTypes.DEPLOYMENT_STATUS, app.id(), d.id(), Map.of("status", status.name()));
        webhookEvents.updateByDeployment(d.id(), "FAILED", error);
        reloadRoutes(out, "Previous application state restored in Caddy");
        appChanged(app.id());
    }

    private ApplicationStatus restoredStatus(String applicationId, ApplicationStatus before) {
        Application fresh = applications.get(applicationId);
        if (fresh.currentDeploymentId() == null) return ApplicationStatus.FAILED;
        return switch (before) {
            case STOPPED, CRASHED -> before;
            default -> ApplicationStatus.RUNNING;
        };
    }

    private void transition(Deployment d, DeploymentStatus status) {
        deployments.updateStatus(d.id(), status);
        events.publish(EventTypes.DEPLOYMENT_STATUS, d.applicationId(), d.id(), Map.of("status", status.name(), "number", d.number()));
        if (status == DeploymentStatus.STARTING) {
            Application app = applications.get(d.applicationId());
            if (app.currentDeploymentId() == null) {
                applications.updateStatus(app.id(), ApplicationStatus.STARTING);
                appChanged(app.id());
            }
        }
    }

    private void reloadRoutes(DeploymentLogger out, String successMessage) {
        try {
            var reload = caddy.reload();
            out.info(reload.applied() ? successMessage : "Caddy: " + reload.message());
        } catch (RuntimeException e) {
            log.warn("Could not update Caddy routes", e);
            out.warn("Could not update Caddy routes: " + e.getMessage());
        }
    }

    private void appChanged(String applicationId) {
        events.publish(EventTypes.APPLICATION_UPDATED, applicationId, null, Map.of("status", applications.get(applicationId).status().name()));
    }

    static String containerName(Application app, ServiceConfig service, int number) {
        return "shipyard-" + app.slug() + "-" + service.name() + "-" + number;
    }

    private static Map<String, String> labels(Application app, Deployment d) {
        Map<String, String> l = new HashMap<>();
        l.put("dev.shipyard.managed", "true");
        l.put("dev.shipyard.application", app.id());
        l.put("dev.shipyard.deployment", d.id());
        l.put("dev.shipyard.deployment-number", String.valueOf(d.number()));
        return l;
    }

    private static String nz(String s) {
        return s == null ? "unknown framework" : s;
    }
}
