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

    /** The network's subnet (e.g. 172.30.0.0/22), or null when unknown. */
    String networkSubnet(String network);

    /** Creates a named volume (labelled) unless it exists. */
    void ensureVolume(String name, Map<String, String> labels);

    /** Names of the volumes carrying {@code label=value}. */
    List<String> listVolumes(String label, String value);

    void removeVolume(String name);

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

    /** Runs a command in a running container with a time limit; exit code -1 and a message when it times out. */
    ExecResult exec(String name, List<String> command, java.time.Duration timeout);

    void removeImage(String tag);

    /** Names of containers carrying the given label value. */
    List<String> listContainers(String label, String value);

    record ExecResult(int exitCode, String output) {}
}
