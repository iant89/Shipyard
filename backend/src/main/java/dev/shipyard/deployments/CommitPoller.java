package dev.shipyard.deployments;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.applications.ApplicationStatus;
import dev.shipyard.repositories.GitRepository;
import dev.shipyard.repositories.GitRepositoryStore;
import dev.shipyard.repositories.RepositoryManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Automatic updates without a webhook: for applications with auto-update on,
 * checks the branch head every 90 seconds and deploys it when it is a commit
 * that has never been deployed. A commit is tried once, so a failing commit is
 * not rebuilt every 90 seconds; the next push is picked up as usual.
 */
@Component
public class CommitPoller {
    private static final Duration LS_REMOTE_TIMEOUT = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(CommitPoller.class);

    private final ApplicationRepository applications;
    private final GitRepositoryStore repositories;
    private final RepositoryManager repoManager;
    private final DeploymentRepository deployments;
    private final DeploymentService deploymentService;
    private final Duration interval;
    private final Map<String, Long> lastChecked = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    // Its own thread: a slow remote must not hold up the shared scheduler (metrics, deployment worker).
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "commit-poller");
        t.setDaemon(true);
        return t;
    });

    public CommitPoller(ApplicationRepository applications, GitRepositoryStore repositories, RepositoryManager repoManager,
                        DeploymentRepository deployments, DeploymentService deploymentService,
                        @Value("${shipyard.auto-update-interval-seconds:90}") long intervalSeconds) {
        this.applications = applications;
        this.repositories = repositories;
        this.repoManager = repoManager;
        this.deployments = deployments;
        this.deploymentService = deploymentService;
        this.interval = Duration.ofSeconds(intervalSeconds);
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
    void tick() {
        if (running.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    pollDue();
                } finally {
                    running.set(false);
                }
            });
        }
    }

    void pollDue() {
        long now = System.nanoTime();
        for (Application app : applications.findAutoUpdating()) {
            Long last = lastChecked.get(app.id());
            if (last != null && now - last < interval.toNanos()) continue;
            lastChecked.put(app.id(), now);
            try {
                poll(app);
            } catch (RuntimeException e) {
                log.warn("Auto-update check failed for {}: {}", app.slug(), e.getMessage());
            }
        }
    }

    /** Checks one application now; returns the queued deployment, or null when there is nothing to do. */
    Deployment poll(Application app) {
        // Not before the first deployment, and a stopped application stays stopped.
        if (app.currentDeploymentId() == null || app.status() == ApplicationStatus.STOPPED) return null;
        if (deployments.hasPending(app.id())) return null;
        GitRepository repo = repositories.forApplication(app.id());
        String head = repoManager.resolveBranchHead(repo, repo.branch(), LS_REMOTE_TIMEOUT);
        if (deployments.existsForCommit(app.id(), head)) return null;
        log.info("Auto-update: {} has a new commit {} on {}", app.slug(), head.substring(0, 7), repo.branch());
        return deploymentService.request(new DeploymentService.DeploymentRequest(app.id(), DeploymentSource.SCHEDULED, null, head,
                repo.branch(), null, null, "auto-update"));
    }
}
