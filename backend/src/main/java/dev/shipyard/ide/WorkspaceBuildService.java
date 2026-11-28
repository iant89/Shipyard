package dev.shipyard.ide;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.applications.ServiceConfig;
import dev.shipyard.applications.ServiceRepository;
import dev.shipyard.builds.BuildArtifact;
import dev.shipyard.builds.BuildEngine;
import dev.shipyard.common.CancellationToken;
import dev.shipyard.detection.DetectionResult;
import dev.shipyard.detection.ProjectDetector;
import dev.shipyard.environment.EnvironmentService;
import dev.shipyard.repositories.GitRepository;
import dev.shipyard.repositories.GitRepositoryStore;
import dev.shipyard.repositories.RepositoryManager;
import dev.shipyard.runtime.ContainerRuntime;
import dev.shipyard.storage.StorageLayout;
import dev.shipyard.security.SecretMasker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/** Runs a safe preview build from the editable worktree without activating it. */
@Service
public class WorkspaceBuildService {
    private static final List<String> BUILD_EXCLUDED_DIRECTORIES =
            List.of(".git", "node_modules", ".next", ".nuxt", ".output", ".svelte-kit", ".cache", ".venv", ".gradle", "target", "coverage");
    private static final List<String> BUILD_EXCLUDED_FILES =
            List.of(".npmrc", ".pypirc", ".netrc", ".git-credentials", "id_rsa", "id_ed25519");

    public record BuildResult(boolean success, String framework, String projectType, List<String> output, String error, long durationMs) {}

    private final ApplicationRepository applications;
    private final ServiceRepository services;
    private final GitRepositoryStore repositories;
    private final RepositoryManager git;
    private final ProjectDetector detector;
    private final BuildEngine builds;
    private final EnvironmentService environment;
    private final ContainerRuntime runtime;
    private final StorageLayout layout;

    public WorkspaceBuildService(ApplicationRepository applications, ServiceRepository services, GitRepositoryStore repositories,
                                 RepositoryManager git, ProjectDetector detector, BuildEngine builds, EnvironmentService environment,
                                 ContainerRuntime runtime, StorageLayout layout) {
        this.applications = applications;
        this.services = services;
        this.repositories = repositories;
        this.git = git;
        this.detector = detector;
        this.builds = builds;
        this.environment = environment;
        this.runtime = runtime;
        this.layout = layout;
    }

    public BuildResult build(String applicationId) {
        Application app = applications.get(applicationId);
        GitRepository repo = repositories.forApplication(applicationId);
        ServiceConfig service = services.primary(applicationId);
        if (!runtime.available()) return new BuildResult(false, null, null, List.of(), "The container runtime is unavailable", 0);

        int number = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        String workKey = "ide-" + UUID.randomUUID();
        Path workDir = layout.root().resolve("builds").resolve(app.id()).resolve(workKey);
        Path sourceDir = workDir.resolve("src");
        Path deploymentDir = layout.root().resolve("deployments").resolve(app.id()).resolve(workKey);
        String imageTag = BuildEngine.imageTag(app.slug() + "-ide", number);
        long started = System.nanoTime();
        List<String> output = new ArrayList<>();
        SecretMasker masker = environment.masker(app.id());
        DetectionResult detection = null;
        BuildArtifact artifact = null;
        String error = null;

        try {
            git.withApplicationLock(applicationId, () -> {
                copyWorkspace(git.ensureWorkspace(repo), sourceDir);
                return null;
            });
            Path buildRoot = sourceDir;
            if (service.rootDirectory() != null && !service.rootDirectory().isBlank() && !service.rootDirectory().equals(".")) {
                buildRoot = sourceDir.resolve(service.rootDirectory()).normalize();
            }
            if (!buildRoot.startsWith(sourceDir) || !Files.isDirectory(buildRoot)) {
                throw new IllegalArgumentException("Configured root directory is missing or escapes the workspace");
            }
            detection = detector.detect(sourceDir, service);
            if (detection.buildStrategy() == null) throw new IllegalStateException(String.join(" ", detection.notes()));
            Map<String, String> labels = new HashMap<>();
            labels.put("dev.shipyard.managed", "true");
            labels.put("dev.shipyard.application", app.id());
            labels.put("dev.shipyard.ide-build", "true");
            int limit = 1_000;
            int[] captured = {0};
            artifact = builds.build(new BuildEngine.BuildContext(app.slug() + "-ide", number, buildRoot, workDir, deploymentDir, detection,
                    environment.buildEnvironment(app.id()), labels, service.cpuLimit(), service.memoryLimitMb()), line -> {
                        if (captured[0]++ < limit) output.add(masker.mask(line));
                    }, new CancellationToken());
            output.add("IDE build completed successfully.");
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? "Build failed" : masker.mask(e.getMessage());
            output.add("IDE build failed: " + error);
        } finally {
            if (artifact != null && artifact.kind() == BuildArtifact.Kind.IMAGE) runtime.removeImage(artifact.imageTag());
            else if (detection != null && detection.projectType() != null && detection.projectType().name().equals("SERVER")) {
                runtime.removeImage(imageTag);
            }
            StorageLayout.deleteRecursively(workDir);
            StorageLayout.deleteRecursively(deploymentDir);
        }
        return new BuildResult(error == null, detection == null ? null : detection.framework(),
                detection == null || detection.projectType() == null ? null : detection.projectType().name(),
                List.copyOf(output), error, java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private static void copyWorkspace(Path from, Path to) {
        try {
            Files.createDirectories(to);
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(from) && BUILD_EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    boolean localEnvironmentFile = (name.equals(".env") || name.startsWith(".env.")) && !name.equals(".env.example");
                    boolean gitMetadataFile = name.equalsIgnoreCase(".git");
                    if (!Files.isSymbolicLink(file) && attrs.isRegularFile() && !gitMetadataFile && !localEnvironmentFile
                            && !BUILD_EXCLUDED_FILES.contains(name)) {
                        Path target = to.resolve(from.relativize(file).toString());
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
