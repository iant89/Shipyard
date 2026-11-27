package dev.shipyard.builds;

import dev.shipyard.common.CancellationToken;
import dev.shipyard.detection.BuildStrategy;
import dev.shipyard.detection.DetectionResult;
import dev.shipyard.detection.ProjectType;
import dev.shipyard.runtime.BuildRequest;
import dev.shipyard.runtime.ContainerRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Converts checked-out source into a deployable artifact. Repository code is
 * only ever executed inside build containers ({@code docker build}).
 */
@Component
public class BuildEngine {

    public record BuildContext(
            String applicationSlug,
            int deploymentNumber,
            Path sourceDir,
            Path workDir,
            Path deploymentDir,
            DetectionResult detection,
            Map<String, String> buildArgs,
            Map<String, String> labels,
            double cpuLimit,
            int memoryLimitMb) {
    }

    private final ContainerRuntime runtime;

    public BuildEngine(ContainerRuntime runtime) {
        this.runtime = runtime;
    }

    public static String imageTag(String slug, int number) {
        return "shipyard/" + slug + ":deployment-" + number;
    }

    public BuildArtifact build(BuildContext ctx, Consumer<String> log, CancellationToken cancel) {
        DetectionResult d = ctx.detection();
        if (d.buildStrategy() == null) {
            throw new IllegalStateException("Build configuration is incomplete: no build strategy was detected or configured");
        }
        log.accept("Build strategy: " + d.buildStrategy() + (d.framework() != null ? " (" + d.framework() + ")" : ""));
        return switch (d.buildStrategy()) {
            case STATIC_FILES -> copyStatic(ctx, log);
            case NODE_STATIC -> buildStatic(ctx, log, cancel);
            case DOCKERFILE -> buildDockerfile(ctx, log, cancel);
            default -> buildGenerated(ctx, log, cancel);
        };
    }

    /** Plain HTML/CSS/JS: files are copied, nothing is executed. */
    private BuildArtifact copyStatic(BuildContext ctx, Consumer<String> log) {
        Path out = ctx.sourceDir().resolve(ctx.detection().outputDirectory() == null ? "." : ctx.detection().outputDirectory()).normalize();
        if (!out.startsWith(ctx.sourceDir())) throw new IllegalArgumentException("Output directory escapes the repository");
        Path dist = ctx.deploymentDir().resolve("dist");
        copyTree(out, dist);
        log.accept("Copied static files to " + dist);
        if (ctx.detection().testCommand() != null) log.accept("Tests skipped: plain static sites have no build container.");
        return BuildArtifact.staticFiles(dist);
    }

    private BuildArtifact buildStatic(BuildContext ctx, Consumer<String> log, CancellationToken cancel) {
        Path dockerfile = writeDockerfile(ctx);
        Path dist = ctx.deploymentDir().resolve("dist");
        runtime.build(new BuildRequest(ctx.sourceDir(), dockerfile, null, DockerfileGenerator.EXPORT_STAGE, dist,
                ctx.buildArgs(), ctx.labels(), ctx.cpuLimit(), ctx.memoryLimitMb()), log, cancel);
        if (!Files.isDirectory(dist) || isEmpty(dist)) {
            throw new IllegalStateException("Build produced no files in output directory '" + ctx.detection().outputDirectory() + "'");
        }
        log.accept("Static artifact exported to " + dist);
        return BuildArtifact.staticFiles(dist);
    }

    private BuildArtifact buildDockerfile(BuildContext ctx, Consumer<String> log, CancellationToken cancel) {
        Path dockerfile = ctx.sourceDir().resolve("Dockerfile");
        if (!Files.isRegularFile(dockerfile)) throw new IllegalStateException("Dockerfile not found in " + ctx.sourceDir().getFileName());
        String tag = imageTag(ctx.applicationSlug(), ctx.deploymentNumber());
        runtime.build(new BuildRequest(ctx.sourceDir(), dockerfile, tag, null, null, ctx.buildArgs(), ctx.labels(),
                ctx.cpuLimit(), ctx.memoryLimitMb()), log, cancel);
        if (ctx.detection().testCommand() != null) {
            log.accept("Note: with a Dockerfile, tests should run as a build stage inside the Dockerfile.");
        }
        log.accept("Built image " + tag);
        return BuildArtifact.image(tag);
    }

    private BuildArtifact buildGenerated(BuildContext ctx, Consumer<String> log, CancellationToken cancel) {
        if (ctx.detection().projectType() != ProjectType.SERVER) throw new IllegalStateException("Unexpected strategy for static project");
        Path dockerfile = writeDockerfile(ctx);
        String tag = imageTag(ctx.applicationSlug(), ctx.deploymentNumber());
        runtime.build(new BuildRequest(ctx.sourceDir(), dockerfile, tag, null, null, ctx.buildArgs(), ctx.labels(),
                ctx.cpuLimit(), ctx.memoryLimitMb()), log, cancel);
        log.accept("Built image " + tag);
        return BuildArtifact.image(tag);
    }

    private Path writeDockerfile(BuildContext ctx) {
        String content = DockerfileGenerator.generate(ctx.detection(), ctx.buildArgs().keySet());
        try {
            Files.createDirectories(ctx.workDir());
            Path f = ctx.workDir().resolve("Dockerfile.shipyard");
            Files.writeString(f, content);
            // Keep a copy next to the artifact so every deployment records exactly how it was built.
            Files.createDirectories(ctx.deploymentDir());
            Files.writeString(ctx.deploymentDir().resolve("Dockerfile.generated"), content);
            return f;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isEmpty(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        } catch (IOException e) {
            return true;
        }
    }

    static void copyTree(Path from, Path to) {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = from.relativize(p);
                String first = rel.getNameCount() > 0 ? rel.getName(0).toString() : "";
                if (first.equals(".git")) continue;
                if (Files.isSymbolicLink(p)) continue; // never follow links out of the source tree
                Path dest = to.resolve(rel.toString());
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
