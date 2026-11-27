package dev.shipyard.storage;

import dev.shipyard.config.PlatformProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Filesystem layout under the data directory:
 *
 * <pre>
 * {dataDir}/
 *   repositories/{applicationId}/      bare-ish working clone
 *   builds/{applicationId}/{n}/        temporary build contexts
 *   deployments/{applicationId}/{n}/   immutable deployment artifacts
 *   deployments/{applicationId}/current -> {n}
 *   artifacts/  logs/  images/  database/  caddy/
 * </pre>
 */
@Component
public class StorageLayout {
    private final Path root;

    public StorageLayout(PlatformProperties props) {
        this.root = Path.of(props.dataDir()).toAbsolutePath();
        for (String dir : new String[] {"repositories", "builds", "deployments", "artifacts", "logs", "images", "database", "caddy"}) {
            mkdirs(root.resolve(dir));
        }
    }

    public Path root() { return root; }
    public Path database() { return root.resolve("database"); }
    public Path repository(String applicationId) { return root.resolve("repositories").resolve(applicationId); }
    public Path buildDir(String applicationId, int number) { return root.resolve("builds").resolve(applicationId).resolve(pad(number)); }
    public Path deploymentsDir(String applicationId) { return root.resolve("deployments").resolve(applicationId); }
    public Path deploymentDir(String applicationId, int number) { return deploymentsDir(applicationId).resolve(pad(number)); }
    public Path caddyDir() { return root.resolve("caddy"); }
    public Path secretKeyFile() { return root.resolve("database").resolve("secret.key"); }

    public static String pad(int number) {
        return String.format("%03d", number);
    }

    public static Path mkdirs(Path p) {
        try {
            return Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Atomically repoint {@code current} at the given deployment directory. */
    public void pointCurrent(String applicationId, int number) {
        Path dir = deploymentsDir(applicationId);
        mkdirs(dir);
        Path link = dir.resolve("current");
        Path tmp = dir.resolve(".current-" + System.nanoTime());
        try {
            Files.createSymbolicLink(tmp, Path.of(pad(number)));
            Files.move(tmp, link, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            throw new UncheckedIOException(e);
        }
    }

    public static void deleteRecursively(Path path) {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) { throw new UncheckedIOException(e); }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
