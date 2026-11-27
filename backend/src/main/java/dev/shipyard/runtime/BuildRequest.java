package dev.shipyard.runtime;

import java.nio.file.Path;
import java.util.Map;

/**
 * An image build. When {@code exportDir} is set the build result (the
 * selected {@code target} stage's filesystem) is exported to that directory
 * instead of producing an image; this is how static sites are built inside
 * containers without running anything on the host.
 */
public record BuildRequest(
        Path contextDir,
        Path dockerfile,
        String tag,
        String target,
        Path exportDir,
        Map<String, String> buildArgs,
        Map<String, String> labels,
        double cpuLimit,
        int memoryLimitMb) {
}
