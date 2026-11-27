package dev.shipyard.builds;

import java.nio.file.Path;

/** Result of a build: either static files on disk or an immutable image tag. */
public record BuildArtifact(Kind kind, Path staticDir, String imageTag) {
    public enum Kind { STATIC, IMAGE }

    public static BuildArtifact staticFiles(Path dir) {
        return new BuildArtifact(Kind.STATIC, dir, null);
    }

    public static BuildArtifact image(String tag) {
        return new BuildArtifact(Kind.IMAGE, null, tag);
    }
}
