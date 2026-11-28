package dev.shipyard.repositories;

import dev.shipyard.common.BadRequestException;
import dev.shipyard.common.CancellationToken;
import dev.shipyard.common.ProcessRunner;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.security.SecretCipher;
import dev.shipyard.storage.StorageLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Git operations through the git CLI. Each application has one local clone
 * under {@code repositories/{applicationId}}; deployments export the exact
 * commit from it with {@code git archive}, never "latest branch".
 */
@Component
public class RepositoryManager {
    public record CommitInfo(String sha, String author, String message, String timestamp) {}

    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{7,40}");
    private static final Pattern GITHUB_SHORT = Pattern.compile("^(?:https?://)?(?:www\\.)?github\\.com[/:]([\\w.-]+)/([\\w.-]+?)(?:\\.git)?/?$");
    private static final Pattern OWNER_REPO = Pattern.compile("^([\\w.-]+)/([\\w.-]+?)(?:\\.git)?$");
    private static final Duration NETWORK_TIMEOUT = Duration.ofMinutes(10);

    private final PlatformProperties props;
    private final StorageLayout layout;
    private final SecretCipher cipher;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public RepositoryManager(PlatformProperties props, StorageLayout layout, SecretCipher cipher) {
        this.props = props;
        this.layout = layout;
        this.cipher = cipher;
    }

    /** Normalises "owner/repo", "github.com/owner/repo" and full URLs to an https clone URL. */
    public static String normalizeUrl(String input) {
        if (input == null || input.isBlank()) throw new BadRequestException("Repository is required");
        String s = input.strip();
        Matcher gh = GITHUB_SHORT.matcher(s);
        if (gh.matches()) return "https://github.com/" + gh.group(1) + "/" + gh.group(2) + ".git";
        Matcher or = OWNER_REPO.matcher(s);
        if (or.matches() && !s.contains(":")) return "https://github.com/" + or.group(1) + "/" + or.group(2) + ".git";
        if (s.startsWith("https://") || s.startsWith("http://") || s.startsWith("file://")) return s;
        throw new BadRequestException("Unsupported repository URL: " + s + " (use https://github.com/owner/repo)");
    }

    /** "owner/repo" for display and webhook matching. */
    public static String fullName(String url) {
        Matcher gh = GITHUB_SHORT.matcher(url.strip());
        if (gh.matches()) return (gh.group(1) + "/" + gh.group(2)).toLowerCase();
        return url.replaceFirst("^https?://", "").replaceFirst("\\.git$", "").toLowerCase();
    }

    public List<String> getBranches(String url, String encryptedToken) {
        String out = git(null, authEnv(encryptedToken), "ls-remote", "--heads", normalizeUrl(url));
        List<String> branches = new ArrayList<>();
        for (String line : out.split("\n")) {
            int idx = line.indexOf("refs/heads/");
            if (idx >= 0) branches.add(line.substring(idx + "refs/heads/".length()).strip());
        }
        return branches;
    }

    /** Resolves the current head commit of a branch on the remote. */
    public String resolveBranchHead(GitRepository repo, String branch) {
        String out = git(null, authEnv(repo.accessTokenEnc()), "ls-remote", repo.url(), "refs/heads/" + branch);
        for (String line : out.split("\n")) {
            String[] parts = line.split("\\s+");
            if (parts.length == 2 && parts[1].equals("refs/heads/" + branch)) return parts[0];
        }
        throw new BadRequestException("Branch not found: " + branch);
    }

    /** Clones the repository if needed, otherwise fetches. */
    public Path cloneOrFetch(GitRepository repo, Consumer<String> log, CancellationToken cancel) {
        Path dir = layout.repository(repo.applicationId());
        ReentrantLock lock = locks.computeIfAbsent(repo.applicationId(), k -> new ReentrantLock());
        lock.lock();
        try {
            Map<String, String> env = authEnv(repo.accessTokenEnc());
            if (!Files.isDirectory(dir.resolve(".git"))) {
                StorageLayout.deleteRecursively(dir);
                StorageLayout.mkdirs(dir.getParent());
                log.accept("Cloning " + fullName(repo.url()));
                stream(List.of(props.gitBinary(), "clone", "--no-checkout", repo.url(), dir.toString()), null, env, log, cancel);
            } else {
                git(dir, Map.of(), "remote", "set-url", "origin", repo.url());
                log.accept("Fetching " + fullName(repo.url()));
                stream(List.of(props.gitBinary(), "fetch", "--prune", "--tags", "origin", "+refs/heads/*:refs/remotes/origin/*"), dir, env, log, cancel);
            }
            return dir;
        } finally {
            lock.unlock();
        }
    }

    /** Ensures the given commit exists locally (fetching it directly if needed) and returns its full SHA. */
    public String resolveCommit(GitRepository repo, String commitish, Consumer<String> log, CancellationToken cancel) {
        Path dir = layout.repository(repo.applicationId());
        String ref = commitish;
        if (!SHA.matcher(commitish).matches()) ref = "refs/remotes/origin/" + commitish;
        ProcessRunner.Result r = ProcessRunner.run(List.of(props.gitBinary(), "rev-parse", "--verify", ref + "^{commit}"), dir, Duration.ofSeconds(30));
        if (!r.ok() && SHA.matcher(commitish).matches()) {
            log.accept("Fetching commit " + commitish);
            stream(List.of(props.gitBinary(), "fetch", "origin", commitish), dir, authEnv(repo.accessTokenEnc()), log, cancel);
            r = ProcessRunner.run(List.of(props.gitBinary(), "rev-parse", "--verify", commitish + "^{commit}"), dir, Duration.ofSeconds(30));
        }
        if (!r.ok()) throw new BadRequestException("Commit or branch not found: " + commitish);
        return r.output().strip();
    }

    /** Commits on a branch that a given commit does not contain, newest first. */
    public record NewerCommits(String headSha, int total, List<CommitInfo> commits) {}

    /**
     * Fetches the repository, then lists the commits on the configured branch
     * that {@code sinceSha} does not contain (up to {@code limit}, newest first;
     * {@code total} is the full count). If {@code sinceSha} is not an ancestor
     * of the branch (another branch was deployed, or history was rewritten),
     * this is every branch commit since the two histories split.
     */
    public NewerCommits newerCommits(GitRepository repo, String sinceSha, int limit) {
        if (!SHA.matcher(sinceSha).matches()) throw new BadRequestException("Invalid commit: " + sinceSha);
        Path dir = cloneOrFetch(repo, line -> { }, new CancellationToken());
        String ref = "refs/remotes/origin/" + repo.branch();
        String head = git(dir, Map.of(), "rev-parse", "--verify", ref + "^{commit}").strip();
        String range = sinceSha + ".." + head;
        int total = Integer.parseInt(git(dir, Map.of(), "rev-list", "--count", range, "--").strip());
        List<CommitInfo> commits = new ArrayList<>();
        if (total > 0) {
            String out = git(dir, Map.of(), "log", "-n", String.valueOf(limit), "--format=%H%x1f%an%x1f%cI%x1f%s%x1e", range, "--");
            for (String rec : out.split("\u001e")) {
                String[] f = rec.strip().split("\u001f", 4);
                if (f.length == 4) commits.add(new CommitInfo(f[0], f[1], f[3].strip(), f[2]));
            }
        }
        return new NewerCommits(head, total, commits);
    }

    public CommitInfo getCommit(String applicationId, String sha) {
        String out = git(layout.repository(applicationId), Map.of(), "log", "-1", "--format=%H%n%an%n%cI%n%s", sha);
        String[] lines = out.split("\n", 4);
        return new CommitInfo(lines[0], lines.length > 1 ? lines[1] : "", lines.length > 3 ? lines[3].strip() : "",
                lines.length > 2 ? lines[2] : "");
    }

    /**
     * Exports the exact commit into {@code target} (equivalent to checking it
     * out, but without a .git directory and without touching the shared clone).
     */
    public void checkoutCommit(String applicationId, String sha, Path target, CancellationToken cancel) {
        Path repoDir = layout.repository(applicationId);
        StorageLayout.deleteRecursively(target);
        StorageLayout.mkdirs(target);
        Path tar = target.resolveSibling(target.getFileName() + ".tar");
        try {
            ProcessRunner.check(List.of(props.gitBinary(), "-C", repoDir.toString(), "archive", "--format=tar", "-o", tar.toString(), sha),
                    null, Map.of(), Duration.ofMinutes(5));
            cancel.throwIfCancelled();
            // -m: give every file the extraction time instead of the commit time. BuildKit decides
            // whether context files changed by size and mtime; with commit times, two commits made in
            // the same second with a same-size edit look identical and the old files would be reused.
            ProcessRunner.check(List.of("tar", "-x", "-m", "-f", tar.toString(), "-C", target.toString()), null, Map.of(), Duration.ofMinutes(5));
        } finally {
            try { Files.deleteIfExists(tar); } catch (java.io.IOException ignored) { }
        }
    }

    public void delete(String applicationId) {
        StorageLayout.deleteRecursively(layout.repository(applicationId));
    }

    /**
     * Supplies a token for private repositories through git's environment
     * configuration so it never appears in argv, URLs or logs.
     */
    private Map<String, String> authEnv(String encryptedToken) {
        Map<String, String> env = new HashMap<>();
        if (encryptedToken != null) {
            String token = cipher.decrypt(encryptedToken);
            String basic = Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        }
        return env;
    }

    private String git(Path dir, Map<String, String> env, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.gitBinary());
        cmd.addAll(List.of(args));
        return ProcessRunner.check(cmd, dir, env, NETWORK_TIMEOUT);
    }

    private void stream(List<String> cmd, Path dir, Map<String, String> env, Consumer<String> log, CancellationToken cancel) {
        ProcessRunner.Result r = ProcessRunner.run(cmd, dir, env, NETWORK_TIMEOUT, log, cancel);
        if (!r.ok()) throw new ProcessRunner.CommandException("git " + cmd.get(1) + " failed (exit " + r.exitCode() + ")", r.exitCode());
    }
}
