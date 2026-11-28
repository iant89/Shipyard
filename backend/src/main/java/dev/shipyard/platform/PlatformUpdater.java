package dev.shipyard.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shipyard.audit.AuditService;
import dev.shipyard.common.BadRequestException;
import dev.shipyard.common.ConflictException;
import dev.shipyard.common.ProcessRunner;
import dev.shipyard.common.Time;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.deployments.DeploymentRepository;
import dev.shipyard.deployments.DeploymentStatus;
import dev.shipyard.repositories.GitRepository;
import dev.shipyard.repositories.RepositoryManager;
import dev.shipyard.repositories.UpdateChecker.Status;
import dev.shipyard.repositories.UpdateChecker.UpdateCheck;
import dev.shipyard.storage.StorageLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Updates Shipyard itself to a newer commit of the branch it was installed
 * from. Checking compares the running commit with the branch on the checkout's
 * origin. Updating starts a helper container (from Shipyard's own image) that
 * runs {@code scripts/self-update.sh} in the host checkout through docker
 * compose: it outlives this container's restart, builds before replacing
 * anything, and rolls back if the new version is not healthy.
 */
@Component
public class PlatformUpdater {
    /** What is installed, whether an update is available, and whether this install can update itself. */
    public record PlatformVersion(String commit, String branch, String repository, UpdateCheck check, boolean canUpdate,
                                  String reason, UpdateStatus update) {}

    /** The last (or running) update, written by scripts/self-update.sh. */
    public record UpdateStatus(String state, String fromSha, String toSha, String message, String startedAt, String finishedAt,
                               List<String> log) {}

    /** Paths and image of this container, as the Docker host sees them. */
    record Self(String image, String hostSource, String hostData, String dockerSocket) {}

    static final String HELPER = "shipyard-updater";
    static final String SOURCE_MOUNT_HINT = "docker-compose.yml mounts the checkout at /opt/shipyard/source";
    private static final String REPO_KEY = "_shipyard";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Pattern SHA40 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern GITHUB_SSH = Pattern.compile("^(?:ssh://)?git@github\\.com[:/]([\\w.-]+)/([\\w.-]+?)(?:\\.git)?/?$");
    private static final Logger log = LoggerFactory.getLogger(PlatformUpdater.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlatformProperties props;
    private final StorageLayout layout;
    private final RepositoryManager repoManager;
    private final DeploymentRepository deployments;
    private final AuditService audit;
    private volatile CachedCheck cached;

    private record CachedCheck(String key, long at, UpdateCheck result) {}

    public PlatformUpdater(PlatformProperties props, StorageLayout layout, RepositoryManager repoManager, DeploymentRepository deployments,
                           AuditService audit) {
        this.props = props;
        this.layout = layout;
        this.repoManager = repoManager;
        this.deployments = deployments;
        this.audit = audit;
    }

    public PlatformVersion version(boolean refresh) {
        Path source = Path.of(props.sourceDir());
        if (!Files.isDirectory(source.resolve(".git"))) {
            return new PlatformVersion(builtCommit(), null, null, null, false,
                    "Self-update needs a docker compose install from a git checkout (" + SOURCE_MOUNT_HINT + ").", status());
        }
        String commit = builtCommit();
        if (commit == null) commit = sourceGit("rev-parse", "HEAD");
        String branch = sourceGit("rev-parse", "--abbrev-ref", "HEAD");
        String origin = sourceGit("remote", "get-url", "origin");
        String url = origin == null ? null : httpsUrl(origin);
        String repository = url == null ? null : RepositoryManager.fullName(url);

        UpdateCheck check = null;
        String reason = null;
        if (commit == null || branch == null || "HEAD".equals(branch) || url == null) {
            reason = "The Shipyard checkout is not on a branch with an origin remote.";
        } else {
            check = check(commit, branch, url, refresh);
        }
        Self self = null;
        if (reason == null) {
            try {
                self = inspectSelf();
            } catch (RuntimeException e) {
                reason = e.getMessage();
            }
        }
        return new PlatformVersion(commit, branch, repository, check, reason == null && self != null, reason, status());
    }

    /** Starts the helper container that updates Shipyard to {@code commit}. */
    public UpdateStatus start(String commit) {
        if (commit == null || !SHA40.matcher(commit).matches()) throw new BadRequestException("A full commit SHA is required");
        UpdateStatus current = status();
        if (current != null && "RUNNING".equals(current.state())) throw new ConflictException("An update is already running");

        PlatformVersion v = version(true);
        if (!v.canUpdate()) throw new ConflictException(v.reason() != null ? v.reason() : "This installation cannot update itself");
        if (v.check() == null || v.check().commits().stream().noneMatch(c -> c.sha().equals(commit))) {
            throw new BadRequestException("Commit " + commit.substring(0, 7) + " is not a newer commit on " + v.branch());
        }
        int active = deployments.findByStatuses(DeploymentStatus.IN_PROGRESS).size() + deployments.findByStatuses(DeploymentStatus.PENDING).size();
        if (active > 0) {
            throw new ConflictException("Wait for the " + active + " queued or running deployment" + (active == 1 ? "" : "s")
                    + " to finish: updating restarts Shipyard and would interrupt " + (active == 1 ? "it" : "them") + ".");
        }

        Self self = inspectSelf();
        writeStatus(new UpdateStatus("RUNNING", v.commit(), commit, "Starting the updater", Time.now(), null, List.of()));
        ProcessRunner.run(List.of(props.dockerBinary(), "rm", "-f", HELPER), null, Duration.ofSeconds(30));
        List<String> cmd = new ArrayList<>(List.of(props.dockerBinary(), "run", "-d", "--name", HELPER,
                "--label", "dev.shipyard.updater=true",
                "-v", self.dockerSocket() + ":/var/run/docker.sock",
                "-v", self.hostSource() + ":" + self.hostSource(),
                "-v", self.hostData() + ":/var/lib/shipyard",
                "-e", "SHIPYARD_UPDATE_DATA_DIR=/var/lib/shipyard",
                "-w", self.hostSource(),
                "--entrypoint", "bash", self.image(), "scripts/self-update.sh", commit));
        ProcessRunner.Result r = ProcessRunner.run(cmd, null, Duration.ofMinutes(2));
        if (!r.ok()) {
            UpdateStatus failed = new UpdateStatus("FAILED", v.commit(), commit, "Could not start the updater: " + lastLine(r.output()),
                    Time.now(), Time.now(), List.of());
            writeStatus(failed);
            return failed;
        }
        audit.record("PLATFORM_UPDATE_STARTED", "PLATFORM", null, Map.of("from", v.commit(), "to", commit));
        log.info("Started Shipyard update {} -> {}", v.commit(), commit);
        cached = null;
        return status();
    }

    /** The last update's outcome and log, or null if Shipyard was never updated from here. */
    public UpdateStatus status() {
        Path file = stateDir().resolve("status.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode n = JSON.readTree(file.toFile());
            String state = text(n, "state");
            String message = text(n, "message");
            String finished = text(n, "finishedAt");
            // The helper wrote RUNNING but is gone (killed, host rebooted): report it rather than spinning forever.
            if ("RUNNING".equals(state) && !helperRunning() && startedLongAgo(text(n, "startedAt"))) {
                state = "FAILED";
                message = "The updater stopped before finishing. See the log; the checkout may need `scripts/self-update.sh` run on the host.";
            }
            return new UpdateStatus(state, text(n, "fromSha"), text(n, "toSha"), message, text(n, "startedAt"), finished, logTail(200));
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ internals

    private UpdateCheck check(String commit, String branch, String url, boolean refresh) {
        String key = commit + "@" + branch + "@" + url;
        CachedCheck c = cached;
        if (!refresh && c != null && c.key().equals(key) && System.nanoTime() - c.at() < TTL.toNanos()) return c.result();
        UpdateCheck result;
        try {
            var repo = new GitRepository(REPO_KEY, REPO_KEY, "github", url, branch, null, null, null);
            var newer = repoManager.newerCommits(repo, commit, 20);
            result = new UpdateCheck(newer.total() == 0 ? Status.UP_TO_DATE : Status.BEHIND, branch, commit, newer.headSha(), newer.total(),
                    newer.commits(), Time.now(), null);
        } catch (RuntimeException e) {
            log.warn("Shipyard update check failed: {}", e.getMessage());
            result = new UpdateCheck(Status.UNAVAILABLE, branch, commit, null, 0, List.of(), Time.now(),
                    "Could not check " + RepositoryManager.fullName(url) + " for updates");
        }
        cached = new CachedCheck(key, System.nanoTime(), result);
        return result;
    }

    /** The commit baked into the image at build time (Dockerfile ARG SHIPYARD_COMMIT), if any. */
    String builtCommit() {
        String c = System.getenv("SHIPYARD_COMMIT");
        return c != null && SHA40.matcher(c.strip()).matches() ? c.strip() : null;
    }

    /** Converts an SSH GitHub remote to https: this container has no SSH keys, and the repository is public. */
    static String httpsUrl(String origin) {
        Matcher m = GITHUB_SSH.matcher(origin.strip());
        if (m.matches()) return "https://github.com/" + m.group(1) + "/" + m.group(2) + ".git";
        return origin.strip();
    }

    Self inspectSelf() {
        String id = System.getenv("HOSTNAME");
        if (id == null || id.isBlank()) throw new IllegalStateException("Shipyard is not running in a container, so it cannot update itself.");
        ProcessRunner.Result r = ProcessRunner.run(List.of(props.dockerBinary(), "inspect", id), null, Duration.ofSeconds(15));
        if (!r.ok()) throw new IllegalStateException("Shipyard cannot inspect its own container (is Docker reachable?), so it cannot update itself.");
        JsonNode c;
        try {
            c = JSON.readTree(r.output()).path(0);
        } catch (IOException e) {
            throw new IllegalStateException("Unexpected output from docker inspect", e);
        }
        String source = null;
        String data = null;
        String socket = null;
        for (JsonNode m : c.path("Mounts")) {
            String dest = m.path("Destination").asText();
            if (dest.equals(props.sourceDir())) source = m.path("Source").asText();
            else if (dest.equals(props.dataDir())) data = m.path("Source").asText();
            else if (dest.equals("/var/run/docker.sock")) socket = m.path("Source").asText();
        }
        if (source == null) throw new IllegalStateException("Shipyard's checkout is not mounted (" + SOURCE_MOUNT_HINT + ").");
        if (data == null) throw new IllegalStateException("Shipyard's data directory is not a bind mount, so the updater cannot back it up.");
        if (socket == null) throw new IllegalStateException("The Docker socket is not mounted at /var/run/docker.sock.");
        return new Self(c.path("Image").asText(), source, data, socket);
    }

    private boolean helperRunning() {
        ProcessRunner.Result r = ProcessRunner.run(List.of(props.dockerBinary(), "inspect", "-f", "{{.State.Running}}", HELPER), null,
                Duration.ofSeconds(10));
        return r.ok() && r.output().strip().equals("true");
    }

    private static boolean startedLongAgo(String startedAt) {
        try {
            return java.time.Instant.parse(startedAt).isBefore(java.time.Instant.now().minusSeconds(60));
        } catch (RuntimeException e) {
            return true;
        }
    }

    private String sourceGit(String... args) {
        List<String> cmd = new ArrayList<>(List.of(props.gitBinary(), "-c", "safe.directory=*", "-C", props.sourceDir()));
        cmd.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.run(cmd, null, Duration.ofSeconds(15));
        return r.ok() && !r.output().isBlank() ? r.output().strip() : null;
    }

    Path stateDir() {
        return layout.root().resolve("platform-update");
    }

    private void writeStatus(UpdateStatus s) {
        try {
            Path dir = StorageLayout.mkdirs(stateDir());
            Path tmp = dir.resolve(".status.json.api");
            var node = JSON.createObjectNode().put("state", s.state()).put("fromSha", s.fromSha()).put("toSha", s.toSha())
                    .put("message", s.message()).put("startedAt", s.startedAt()).put("finishedAt", s.finishedAt());
            Files.writeString(tmp, JSON.writeValueAsString(node));
            Files.move(tmp, dir.resolve("status.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(dir.resolve("update.log"), "");
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the update status", e);
        }
    }

    private List<String> logTail(int lines) {
        Path file = stateDir().resolve("update.log");
        if (!Files.isRegularFile(file)) return List.of();
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return all.subList(Math.max(0, all.size() - lines), all.size());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String lastLine(String output) {
        String[] lines = output.strip().split("\n");
        return lines[lines.length - 1];
    }
}
