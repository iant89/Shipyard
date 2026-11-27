package dev.shipyard.runtime;

import dev.shipyard.common.CancellationToken;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Container runtime abstraction. Nothing outside this package may depend on
 * Docker-specific behaviour; a Podman implementation can be added later.
 */
public interface ContainerRuntime {

    /** Whether the runtime is reachable. */
    boolean available();

    String version();

    void ensureNetwork(String network);

    /** Builds an image (or exports a build stage when {@link BuildRequest#exportDir()} is set). */
    void build(BuildRequest request, Consumer<String> log, CancellationToken cancel);

    /** Creates a container (not started). Returns the container id. */
    String create(ContainerSpec spec);

    void start(String name);

    void stop(String name, int timeoutSeconds);

    void restart(String name);

    void remove(String name);

    List<String> logs(String name, int tail);

    /** Follows logs until the returned handle is closed. */
    AutoCloseable followLogs(String name, int tail, Consumer<String> line);

    Optional<ContainerInfo> inspect(String name);

    Optional<ContainerStats> stats(String name);

    /** Stats for all running containers managed by the platform, keyed by container name. */
    Map<String, ContainerStats> statsAll();

    /** Runs a command inside a running container. */
    ExecResult exec(String name, List<String> command);

    void removeImage(String tag);

    /** Names of containers carrying the given label value. */
    List<String> listContainers(String label, String value);

    record ExecResult(int exitCode, String output) {}
}
