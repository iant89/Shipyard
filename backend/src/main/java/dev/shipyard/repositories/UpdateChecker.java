package dev.shipyard.repositories;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.common.Time;
import dev.shipyard.deployments.Deployment;
import dev.shipyard.deployments.DeploymentRepository;
import dev.shipyard.repositories.RepositoryManager.CommitInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Answers "is this application running the latest commit of its branch?"
 * for the application page. Results are cached briefly so that opening the
 * page repeatedly does not fetch from the remote every time.
 */
@Component
public class UpdateChecker {
    public enum Status { UP_TO_DATE, BEHIND, NOT_DEPLOYED, UNAVAILABLE }

    /**
     * {@code commits} are newest first and capped at {@link #MAX_COMMITS};
     * {@code behind} is the full count of newer commits.
     */
    public record UpdateCheck(Status status, String branch, String currentSha, String latestSha, int behind,
                              List<CommitInfo> commits, String checkedAt, String message) {}

    static final int MAX_COMMITS = 20;
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);

    private record Cached(String key, long at, UpdateCheck result) {}

    private final ApplicationRepository applications;
    private final DeploymentRepository deployments;
    private final GitRepositoryStore repositories;
    private final RepositoryManager repoManager;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public UpdateChecker(ApplicationRepository applications, DeploymentRepository deployments, GitRepositoryStore repositories,
                         RepositoryManager repoManager) {
        this.applications = applications;
        this.deployments = deployments;
        this.repositories = repositories;
        this.repoManager = repoManager;
    }

    public UpdateCheck check(String applicationId, boolean refresh) {
        Application app = applications.get(applicationId);
        GitRepository repo = repositories.forApplication(app.id());
        Deployment current = app.currentDeploymentId() == null ? null : deployments.get(app.currentDeploymentId());
        if (current == null || current.commitSha() == null) {
            return new UpdateCheck(Status.NOT_DEPLOYED, repo.branch(), null, null, 0, List.of(), Time.now(), null);
        }

        // Keyed by what is deployed and what is tracked: a new deployment or branch change misses the cache.
        String key = current.commitSha() + "@" + repo.branch() + "@" + repo.url();
        Cached hit = cache.get(app.id());
        if (!refresh && hit != null && hit.key().equals(key) && System.nanoTime() - hit.at() < TTL.toNanos()) return hit.result();

        UpdateCheck result;
        try {
            var newer = repoManager.newerCommits(repo, current.commitSha(), MAX_COMMITS);
            result = new UpdateCheck(newer.total() == 0 ? Status.UP_TO_DATE : Status.BEHIND, repo.branch(), current.commitSha(),
                    newer.headSha(), newer.total(), newer.commits(), Time.now(), null);
        } catch (RuntimeException e) {
            log.warn("Update check failed for {}: {}", app.slug(), e.getMessage());
            result = new UpdateCheck(Status.UNAVAILABLE, repo.branch(), current.commitSha(), null, 0, List.of(), Time.now(),
                    "Could not check " + RepositoryManager.fullName(repo.url()) + " for new commits");
        }
        cache.put(app.id(), new Cached(key, System.nanoTime(), result));
        return result;
    }
}
