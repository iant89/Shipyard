package dev.shipyard.repositories;

import dev.shipyard.common.BadRequestException;
import dev.shipyard.common.CancellationToken;
import dev.shipyard.common.ConflictException;
import dev.shipyard.common.NotFoundException;
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
import java.util.function.Supplier;
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
        return resolveBranchHead(repo, branch, NETWORK_TIMEOUT);
    }

    /** As {@link #resolveBranchHead(GitRepository, String)}, giving up after {@code timeout}. */
    public String resolveBranchHead(GitRepository repo, String branch, Duration timeout) {
        List<String> cmd = List.of(props.gitBinary(), "ls-remote", repo.url(), "refs/heads/" + branch);
        String out = ProcessRunner.check(cmd, null, authEnv(repo.accessTokenEnc()), timeout);
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

    public record WorkspaceGitState(String branch, String headSha, List<String> changes) {}
    public record WorkspaceChangedFile(String path, String status) {}
    public record WorkspaceCommitFiles(List<WorkspaceChangedFile> files, boolean truncated) {}
    public record WorkspaceCommitDiff(String commitSha, String path, String diff, boolean truncated) {}

    private static final int MAX_WORKSPACE_HISTORY = 100;
    private static final int MAX_COMMIT_FILES = 500;
    private static final int MAX_DIFF_OUTPUT_CHARS = 1_000_000;

    /** Lists cached remote-tracking branches without starting a network request. */
    public List<String> workspaceBranches(GitRepository repo) {
        return withApplicationLock(repo.applicationId(), () -> {
            Path dir = ensureWorkspace(repo);
            String refs = git(dir, Map.of(), "for-each-ref", "--format=%(refname:short)", "refs/remotes/origin");
            List<String> branches = new ArrayList<>();
            for (String ref : refs.lines().toList()) {
                if (ref.startsWith("origin/") && !ref.equals("origin/HEAD")) branches.add(ref.substring("origin/".length()));
            }
            return branches.stream().distinct().sorted().toList();
        });
    }

    /** Run a workspace operation under the same per-application lock used by fetches and deployments. */
    public <T> T withApplicationLock(String applicationId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(applicationId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Returns the editable worktree, initializing it from the configured branch on first use. */
    public Path ensureWorkspace(GitRepository repo) {
        return withApplicationLock(repo.applicationId(), () -> {
            Path dir = layout.repository(repo.applicationId());
            Path gitDir = dir.resolve(".git");
            Path marker = gitDir.resolve("shipyard-ide-workspace");
            if (!Files.isDirectory(gitDir) || !Files.isRegularFile(marker)) {
                cloneOrFetch(repo, line -> { }, new CancellationToken());
                if (!Files.isRegularFile(marker)) {
                    checkoutRemoteBranch(dir, repo.branch(), true);
                    try {
                        Files.writeString(marker, "managed by Shipyard IDE", StandardCharsets.UTF_8);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            }
            return dir;
        });
    }

    /** Refreshes remote-tracking branches for the IDE without changing the checked-out worktree. */
    public void fetchWorkspace(GitRepository repo) {
        withApplicationLock(repo.applicationId(), () -> {
            Path marker = layout.repository(repo.applicationId()).resolve(".git/shipyard-ide-workspace");
            if (Files.isRegularFile(marker)) cloneOrFetch(repo, line -> { }, new CancellationToken());
            else ensureWorkspace(repo);
            return null;
        });
    }

    /** Switches the IDE worktree to a remote branch without discarding local changes. */
    public Path checkoutWorkspaceBranch(GitRepository repo, String branch) {
        return withApplicationLock(repo.applicationId(), () -> {
            Path dir = ensureWorkspace(repo);
            cloneOrFetch(repo, line -> { }, new CancellationToken());
            String current = git(dir, Map.of(), "branch", "--show-current").strip();
            if (current.equals(branch)) return dir;
            if (!git(dir, Map.of(), "status", "--porcelain=v1", "--untracked-files=all").isBlank()) {
                throw new ConflictException("Commit or discard workspace changes before switching branches");
            }
            checkoutRemoteBranch(dir, branch, false);
            return dir;
        });
    }

    public WorkspaceGitState workspaceGitState(GitRepository repo) {
        return withApplicationLock(repo.applicationId(), () -> {
            Path dir = ensureWorkspace(repo);
            String branch = git(dir, Map.of(), "branch", "--show-current").strip();
            String head = git(dir, Map.of(), "rev-parse", "HEAD").strip();
            String status = git(dir, Map.of(), "status", "--porcelain=v1", "--untracked-files=all");
            List<String> changes = status.lines().filter(line -> !line.isBlank()).toList();
            return new WorkspaceGitState(branch, head, changes);
        });
    }

    /** Most recent commits on the currently checked-out workspace branch. */
    public List<CommitInfo> workspaceHistory(GitRepository repo, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_WORKSPACE_HISTORY));
        return withApplicationLock(repo.applicationId(), () -> {
            Path dir = ensureWorkspace(repo);
            String output = git(dir, Map.of(), "log", "-n", String.valueOf(safeLimit),
                    "--format=%H%x1f%an%x1f%cI%x1f%s%x1e", "HEAD", "--");
            List<CommitInfo> commits = new ArrayList<>();
            for (String record : output.split(String.valueOf((char) 0x1e))) {
                String[] fields = record.strip().split(String.valueOf((char) 0x1f), 4);
                if (fields.length == 4) commits.add(new CommitInfo(fields[0], fields[1], fields[3].strip(), fields[2]));
            }
            return List.copyOf(commits);
        });
    }

    /** Files changed from a commit's first parent, with bounded output for large commits. */
    public WorkspaceCommitFiles workspaceCommitFiles(GitRepository repo, String requestedSha) {
        return withApplicationLock(repo.applicationId(), () -> {
            Path dir = ensureWorkspace(repo);
            String sha = resolveWorkspaceCommit(dir, requestedSha);
            return workspaceCommitFilesLocked(dir, sha);
        });
    }

    /** A bounded, literal-path diff for one file changed by a commit. */
    public WorkspaceCommitDiff workspaceCommitDiff(GitRepository repo, String requestedSha, String path) {
        validateWorkspaceHistoryPath(path);
        return withApplicationLock(repo.applicationId(), () -> {
            Path dir = ensureWorkspace(repo);
            String sha = resolveWorkspaceCommit(dir, requestedSha);
            WorkspaceCommitFiles changed = workspaceCommitFilesLocked(dir, sha);
            if (changed.files().stream().noneMatch(file -> file.path().equals(path))) {
                throw new NotFoundException("File is not part of this commit");
            }
            List<String> parents = workspaceCommitParents(dir, sha);
            List<String> command = new ArrayList<>(List.of(props.gitBinary(), "--literal-pathspecs"));
            if (parents.isEmpty()) {
                command.addAll(List.of("show", "--format=", "--no-ext-diff", "--no-textconv", "--unified=3", "--root", sha, "--", path));
            } else {
                command.addAll(List.of("diff", "--no-ext-diff", "--no-textconv", "--unified=3", parents.get(0), sha, "--", path));
            }
            ProcessRunner.Result result = ProcessRunner.run(command, dir, Duration.ofSeconds(30));
            if (!result.ok()) throw new ProcessRunner.CommandException("Could not read the commit diff", result.exitCode());
            String diff = result.output();
            return new WorkspaceCommitDiff(sha, path, diff, diff.length() >= MAX_DIFF_OUTPUT_CHARS);
        });
    }

    /** Creates a local commit from the IDE worktree; no push is performed. */
    public String commitWorkspace(GitRepository repo, String message) {
        if (message == null || message.isBlank()) throw new BadRequestException("Commit message is required");
        String cleanMessage = message.strip();
        if (cleanMessage.length() > 500) throw new BadRequestException("Commit message is too long (max 500 characters)");
        return withApplicationLock(repo.applicationId(), () -> {
            Path dir = ensureWorkspace(repo);
            git(dir, Map.of(), "add", "--all");
            ProcessRunner.Result diff = ProcessRunner.run(List.of(props.gitBinary(), "diff", "--cached", "--quiet"), dir,
                    Duration.ofSeconds(30));
            if (diff.exitCode() == 0) return git(dir, Map.of(), "rev-parse", "HEAD").strip();
            if (diff.exitCode() != 1) throw new ProcessRunner.CommandException("Could not inspect staged IDE changes", diff.exitCode());
            git(dir, Map.of(), "-c", "user.name=Shipyard IDE", "-c", "user.email=shipyard-ide@localhost", "commit", "-m", cleanMessage);
            return git(dir, Map.of(), "rev-parse", "HEAD").strip();
        });
    }

    /** Pushes the checked-out IDE branch using the repository's stored credential, if any. */
    public void pushWorkspace(GitRepository repo, String branch) {
        withApplicationLock(repo.applicationId(), () -> {
            Path dir = ensureWorkspace(repo);
            String current = git(dir, Map.of(), "branch", "--show-current").strip();
            if (!current.equals(branch)) throw new ConflictException("The IDE is no longer on branch " + branch);
            validateBranch(branch);
            git(dir, authEnv(repo.accessTokenEnc()), "push", "origin", "HEAD:refs/heads/" + branch);
            return null;
        });
    }

    private WorkspaceCommitFiles workspaceCommitFilesLocked(Path dir, String sha) {
        String parent = workspaceFirstParent(dir, sha);
        List<String> command = new ArrayList<>(List.of(props.gitBinary(), "diff-tree", "--no-commit-id", "--name-status",
                "--no-renames", "-r", "-z"));
        if (parent == null) command.add("--root");
        else command.add(parent);
        command.add(sha);
        ProcessRunner.Result result = ProcessRunner.run(command, dir, Duration.ofSeconds(30));
        if (!result.ok()) throw new ProcessRunner.CommandException("Could not list files in the commit", result.exitCode());

        String[] fields = result.output().split(String.valueOf((char) 0), -1);
        List<WorkspaceChangedFile> files = new ArrayList<>();
        boolean truncated = result.output().length() >= MAX_DIFF_OUTPUT_CHARS;
        for (int i = 0; i + 1 < fields.length; i += 2) {
            String status = fields[i];
            String path = fields[i + 1];
            if (status.isBlank() || path.isBlank()) continue;
            if (!safeWorkspaceHistoryPath(path)) {
                truncated = true;
                continue;
            }
            if (files.size() >= MAX_COMMIT_FILES) {
                truncated = true;
                break;
            }
            files.add(new WorkspaceChangedFile(path, status));
        }
        return new WorkspaceCommitFiles(List.copyOf(files), truncated);
    }

    private String workspaceFirstParent(Path dir, String sha) {
        String parents = git(dir, Map.of(), "rev-list", "--parents", "-n", "1", sha).strip();
        String[] fields = parents.split("\\s+");
        return fields.length > 1 ? fields[1] : null;
    }

    private String resolveWorkspaceCommit(Path dir, String requestedSha) {
        if (requestedSha == null || !SHA.matcher(requestedSha).matches()) throw new BadRequestException("Invalid commit SHA");
        ProcessRunner.Result result = ProcessRunner.run(List.of(props.gitBinary(), "rev-parse", "--verify", requestedSha + "^{commit}"),
                dir, Duration.ofSeconds(30));
        if (!result.ok()) throw new NotFoundException("Commit not found");
        String sha = result.output().strip();
        if (!SHA.matcher(sha).matches()) throw new NotFoundException("Commit not found");
        return sha;
    }

    private static void validateWorkspaceHistoryPath(String path) {
        if (!safeWorkspaceHistoryPath(path)) throw new BadRequestException("Use a safe project-relative file path");
    }

    private static boolean safeWorkspaceHistoryPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.matches("^[A-Za-z]:.*") || path.chars().anyMatch(Character::isISOControl)) return false;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")) return false;
        }
        return true;
    }

    private void checkoutRemoteBranch(Path dir, String branch, boolean reset) {
        validateBranch(branch);
        String remoteRef = "refs/remotes/origin/" + branch;
        ProcessRunner.Result remote = ProcessRunner.run(List.of(props.gitBinary(), "show-ref", "--verify", "--quiet", remoteRef), dir,
                Duration.ofSeconds(30));
        if (!remote.ok()) throw new BadRequestException("Remote branch not found: " + branch);
        if (reset || !refExists(dir, "refs/heads/" + branch)) {
            git(dir, Map.of(), "checkout", "-B", branch, "origin/" + branch);
        } else {
            git(dir, Map.of(), "checkout", branch);
        }
    }

    private void validateBranch(String branch) {
        if (branch == null || branch.isBlank()) throw new BadRequestException("Branch is required");
        ProcessRunner.Result result = ProcessRunner.run(List.of(props.gitBinary(), "check-ref-format", "--branch", branch), null,
                Duration.ofSeconds(10));
        if (!result.ok()) throw new BadRequestException("Invalid branch: " + branch);
    }

    private boolean refExists(Path dir, String ref) {
        return ProcessRunner.run(List.of(props.gitBinary(), "show-ref", "--verify", "--quiet", ref), dir, Duration.ofSeconds(30)).ok();
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
        withApplicationLock(applicationId, () -> {
            StorageLayout.deleteRecursively(layout.repository(applicationId));
            return null;
        });
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
