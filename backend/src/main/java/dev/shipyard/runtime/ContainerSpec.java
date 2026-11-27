package dev.shipyard.runtime;

import java.util.List;
import java.util.Map;

/** Everything needed to create an application container. */
public record ContainerSpec(
        String name,
        String image,
        Map<String, String> env,
        String workingDir,
        Integer internalPort,
        double cpuLimit,
        int memoryLimitMb,
        String restartPolicy,
        String network,
        List<String> networkAliases,
        List<String> volumes,
        Map<String, String> labels) {
}
