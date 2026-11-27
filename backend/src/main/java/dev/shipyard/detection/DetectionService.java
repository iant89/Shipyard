package dev.shipyard.detection;

import dev.shipyard.applications.ServiceConfig;
import dev.shipyard.common.BadRequestException;
import dev.shipyard.common.ProcessRunner;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.repositories.GitRepository;
import dev.shipyard.repositories.RepositoryManager;
import dev.shipyard.security.SecretCipher;
import dev.shipyard.storage.StorageLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Detection preview for the Add Application wizard: shallow-clones the branch
 * into a scratch directory, runs the detector (reading files only, nothing
 * is executed) and deletes the clone.
 */
@Service
public class DetectionService {
    public record Preview(String repository, String branch, String commitSha, List<String> branches, DetectionResult detection) {}

    private final ProjectDetector detector;
    private final RepositoryManager repositories;
    private final StorageLayout layout;
    private final PlatformProperties props;
    private final SecretCipher cipher;

    public DetectionService(ProjectDetector detector, RepositoryManager repositories, StorageLayout layout, PlatformProperties props,
                            SecretCipher cipher) {
        this.detector = detector;
        this.repositories = repositories;
        this.layout = layout;
        this.props = props;
        this.cipher = cipher;
    }

    public List<String> branches(String repository, String accessToken) {
        return repositories.getBranches(repository, encrypt(accessToken));
    }

    public Preview preview(String repository, String branch, String accessToken, String rootDirectory) {
        ServiceConfig explicit = rootDirectory == null || rootDirectory.isBlank() ? null
                : new ServiceConfig(null, null, "web", null, null, null, rootDirectory, null, null, null, null, null, null, null,
                null, null, null, 60, 1, 512, null, 0, null, null);
        return preview(repository, branch, accessToken, explicit);
    }

    Preview preview(String repository, String branch, String accessToken, ServiceConfig explicit) {
        String url = RepositoryManager.normalizeUrl(repository);
        String enc = encrypt(accessToken);
        List<String> branches = repositories.getBranches(url, enc);
        if (branches.isEmpty()) throw new BadRequestException("Repository has no branches or is not accessible");
        String b = branch == null || branch.isBlank() ? (branches.contains("main") ? "main" : branches.contains("master") ? "master" : branches.get(0)) : branch;
        if (!branches.contains(b)) throw new BadRequestException("Branch not found: " + b);
        Path dir = layout.root().resolve("builds").resolve("detect-" + System.nanoTime());
        try {
            List<String> cmd = new ArrayList<>(List.of(props.gitBinary(), "clone", "--depth", "1", "--single-branch", "--branch", b, url, dir.toString()));
            ProcessRunner.check(cmd, null, authEnv(accessToken), Duration.ofMinutes(5));
            String sha = ProcessRunner.check(List.of(props.gitBinary(), "-C", dir.toString(), "rev-parse", "HEAD"), null, Map.of(), Duration.ofSeconds(30)).strip();
            return new Preview(RepositoryManager.fullName(url), b, sha, branches, detector.detect(dir, explicit));
        } finally {
            StorageLayout.deleteRecursively(dir);
        }
    }

    public List<String> branchesFor(GitRepository repo) {
        return repositories.getBranches(repo.url(), repo.accessTokenEnc());
    }

    /** Re-detects an existing application's current branch head. */
    public DetectionResult detectFor(GitRepository repo, ServiceConfig service) {
        String token = repo.accessTokenEnc() == null ? null : cipher.decrypt(repo.accessTokenEnc());
        return preview(repo.url(), repo.branch(), token, service).detection();
    }

    private String encrypt(String token) {
        return token == null || token.isBlank() ? null : cipher.encrypt(token.strip());
    }

    private static Map<String, String> authEnv(String token) {
        Map<String, String> env = new HashMap<>();
        if (token != null && !token.isBlank()) {
            String basic = Base64.getEncoder().encodeToString(("x-access-token:" + token.strip()).getBytes(StandardCharsets.UTF_8));
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        }
        return env;
    }
}
