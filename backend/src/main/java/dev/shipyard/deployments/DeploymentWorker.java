package dev.shipyard.deployments;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.applications.ApplicationStatus;
import dev.shipyard.caddy.CaddyManager;
import dev.shipyard.common.CancellationToken;
import dev.shipyard.config.PlatformProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background worker that drains the persistent deployment queue. At most one
 * item runs per application; different applications deploy concurrently
 * (bounded by {@code shipyard.worker-threads}).
 */
@Component
public class DeploymentWorker {
    private static final Logger log = LoggerFactory.getLogger(DeploymentWorker.class);

    private record Running(String deploymentId, CancellationToken token) {}

    private final DeploymentQueue queue;
    private final DeploymentRepository deployments;
    private final ApplicationRepository applications;
    private final DeploymentPipeline pipeline;
    private final CaddyManager caddy;
    private final ExecutorService executor;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final int capacity;
    private volatile boolean ready;

    public DeploymentWorker(DeploymentQueue queue, DeploymentRepository deployments, ApplicationRepository applications,
                            DeploymentPipeline pipeline, CaddyManager caddy, PlatformProperties props) {
        this.queue = queue;
        this.deployments = deployments;
        this.applications = applications;
        this.pipeline = pipeline;
        this.caddy = caddy;
        this.capacity = props.workerThreads();
        ThreadFactory tf = Thread.ofPlatform().name("deploy-worker-", 1).daemon().factory();
        this.executor = Executors.newFixedThreadPool(capacity, tf);
    }

    /** Recovers from an unclean shutdown: anything that was mid-flight is marked failed. */
    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        boolean routesChanged = false;
        for (QueueItem q : queue.byStatus("RUNNING")) {
            queue.finish(q.id(), "FAILED");
            deployments.findById(q.deploymentId()).ifPresent(d -> {
                if (QueueItem.DEPLOY.equals(q.kind()) && !d.status().isTerminal() && d.status() != DeploymentStatus.RUNNING) {
                    deployments.finish(d.id(), DeploymentStatus.FAILED, "Interrupted by platform restart");
                }
            });
            Application app = applications.findById(q.applicationId()).orElse(null);
            if (app != null && app.status().isUpdating()) {
                applications.updateStatus(app.id(), restoredStatus(app));
                routesChanged = true;
            }
            log.warn("Marked interrupted deployment {} as failed", q.deploymentId());
        }
        if (routesChanged) caddy.reloadAsync();
        ready = true;
        wake();
    }

    private ApplicationStatus restoredStatus(Application app) {
        if (app.currentDeploymentId() == null) return ApplicationStatus.FAILED;
        Deployment current = deployments.findById(app.currentDeploymentId()).orElse(null);
        if (current == null) return ApplicationStatus.FAILED;
        if (current.status() == DeploymentStatus.STOPPED || "STOPPED".equals(current.runtimeStatus())) return ApplicationStatus.STOPPED;
        if ("CRASHED".equals(current.runtimeStatus())) return ApplicationStatus.CRASHED;
        return ApplicationStatus.RUNNING;
    }

    public void wake() {
        if (ready) Thread.ofVirtual().start(this::poll);
    }

    @Scheduled(fixedDelay = 2000)
    synchronized void poll() {
        if (!ready) return;
        for (QueueItem item : queue.runnable()) {
            if (running.size() >= capacity) return;
            if (running.containsKey(item.applicationId())) continue;
            if (!queue.claim(item.id())) continue;
            CancellationToken token = new CancellationToken();
            running.put(item.applicationId(), new Running(item.deploymentId(), token));
            executor.submit(() -> run(item, token));
        }
    }

    private void run(QueueItem item, CancellationToken token) {
        String result = "FAILED";
        try {
            result = pipeline.execute(item, token);
        } catch (RuntimeException e) {
            log.error("Unexpected error executing queue item {}", item.id(), e);
            deployments.finish(item.deploymentId(), DeploymentStatus.FAILED, "Internal error: " + e.getMessage());
        } finally {
            queue.finish(item.id(), result);
            running.remove(item.applicationId());
            wake();
        }
    }

    public boolean cancelRunning(String applicationId, String deploymentId) {
        Running r = running.get(applicationId);
        if (r == null || !r.deploymentId().equals(deploymentId)) return false;
        r.token().cancel();
        return true;
    }

    public boolean isBusy(String applicationId) {
        return running.containsKey(applicationId);
    }
}
