package dev.shipyard.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shipyard.common.CancellationToken;
import dev.shipyard.common.ProcessRunner;
import dev.shipyard.config.PlatformProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Docker implementation of {@link ContainerRuntime}, driving the docker CLI.
 * Using the CLI (rather than a client library) keeps behaviour identical to
 * what an operator would type, which makes problems easy to reproduce.
 */
@Component
public class DockerRuntime implements ContainerRuntime {
    private static final Logger log = LoggerFactory.getLogger(DockerRuntime.class);
    private static final Duration SHORT = Duration.ofSeconds(60);
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(45);
    private static final Pattern SIZE = Pattern.compile("([0-9.]+)\\s*([KMGTP]?i?B)?", Pattern.CASE_INSENSITIVE);

    private final PlatformProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public DockerRuntime(PlatformProperties props) {
        this.props = props;
    }

    @Override
    public boolean available() {
        try {
            return ProcessRunner.run(List.of(docker(), "version", "--format", "{{.Server.Version}}"), null, Duration.ofSeconds(10)).ok();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String version() {
        try {
            ProcessRunner.Result r = ProcessRunner.run(List.of(docker(), "version", "--format", "{{.Server.Version}}"), null, Duration.ofSeconds(10));
            return r.ok() ? r.output().strip() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void ensureNetwork(String network) {
        if (ProcessRunner.run(List.of(docker(), "network", "inspect", network), null, SHORT).ok()) return;
        List<String> cmd = new ArrayList<>(List.of(docker(), "network", "create", "--driver", "bridge", "--label", "dev.shipyard.managed=true"));
        // A fixed subnet lets applications trust their proxy (Caddy) by address range.
        if (props.dockerSubnet() != null && !props.dockerSubnet().isBlank()) cmd.addAll(List.of("--subnet", props.dockerSubnet().strip()));
        cmd.add(network);
        ProcessRunner.check(cmd, null, Map.of(), SHORT);
        log.info("Created docker network {}{}", network, props.dockerSubnet() == null || props.dockerSubnet().isBlank() ? "" : " (" + props.dockerSubnet() + ")");
    }

    @Override
    public void build(BuildRequest req, Consumer<String> logSink, CancellationToken cancel) {
        List<String> cmd = new ArrayList<>(List.of(docker(), "build", "--progress=plain", "--pull",
                "-f", req.dockerfile().toString()));
        if (req.target() != null) cmd.addAll(List.of("--target", req.target()));
        if (req.exportDir() != null) {
            cmd.addAll(List.of("--output", "type=local,dest=" + req.exportDir()));
        } else {
            cmd.addAll(List.of("-t", req.tag()));
        }
        req.buildArgs().forEach((k, v) -> cmd.addAll(List.of("--build-arg", k)));
        req.labels().forEach((k, v) -> cmd.addAll(List.of("--label", k + "=" + v)));
        cmd.add(req.contextDir().toString());
        // Build args are passed by name only; their values come from the environment so they never appear in argv.
        Map<String, String> env = new HashMap<>(req.buildArgs());
        env.put("DOCKER_BUILDKIT", "1");
        ProcessRunner.Result r = ProcessRunner.run(cmd, null, env, BUILD_TIMEOUT, logSink, cancel);
        if (!r.ok()) throw new ProcessRunner.CommandException("docker build failed (exit " + r.exitCode() + ")", r.exitCode());
    }

    @Override
    public String create(ContainerSpec spec) {
        for (String v : spec.volumes()) {
            if (v.contains("docker.sock")) throw new IllegalArgumentException("Mounting the Docker socket into applications is not allowed");
        }
        Path envFile = writeEnvFile(spec.env());
        try {
            List<String> cmd = new ArrayList<>(List.of(docker(), "create",
                    "--name", spec.name(),
                    "--network", spec.network(),
                    "--restart", restartPolicy(spec.restartPolicy()),
                    "--cpus", String.valueOf(spec.cpuLimit()),
                    "--memory", spec.memoryLimitMb() + "m",
                    "--memory-swap", spec.memoryLimitMb() + "m",
                    "--pids-limit", "1024",
                    "--security-opt", "no-new-privileges",
                    "--log-opt", "max-size=10m", "--log-opt", "max-file=3",
                    "--env-file", envFile.toString()));
            for (String alias : spec.networkAliases()) cmd.addAll(List.of("--network-alias", alias));
            if (spec.workingDir() != null) cmd.addAll(List.of("-w", spec.workingDir()));
            for (String v : spec.volumes()) cmd.addAll(List.of("-v", v));
            spec.labels().forEach((k, v) -> cmd.addAll(List.of("--label", k + "=" + v)));
            cmd.add(spec.image());
            return ProcessRunner.check(cmd, null, Map.of(), SHORT).strip();
        } finally {
            try { Files.deleteIfExists(envFile); } catch (IOException ignored) { }
        }
    }

    @Override
    public void start(String name) {
        ProcessRunner.check(List.of(docker(), "start", name), null, Map.of(), SHORT);
    }

    @Override
    public void stop(String name, int timeoutSeconds) {
        ProcessRunner.run(List.of(docker(), "stop", "-t", String.valueOf(timeoutSeconds), name), null, Duration.ofSeconds(timeoutSeconds + 30L));
    }

    @Override
    public void restart(String name) {
        ProcessRunner.check(List.of(docker(), "restart", "-t", "10", name), null, Map.of(), Duration.ofSeconds(90));
    }

    @Override
    public void remove(String name) {
        ProcessRunner.run(List.of(docker(), "rm", "-f", "-v", name), null, SHORT);
    }

    @Override
    public List<String> logs(String name, int tail) {
        ProcessRunner.Result r = ProcessRunner.run(List.of(docker(), "logs", "--timestamps", "--tail", String.valueOf(tail), name), null, SHORT);
        if (!r.ok()) return List.of();
        return r.output().lines().toList();
    }

    @Override
    public AutoCloseable followLogs(String name, int tail, Consumer<String> line) {
        ProcessBuilder pb = new ProcessBuilder(docker(), "logs", "--timestamps", "--follow", "--tail", String.valueOf(tail), name)
                .redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) line.accept(l);
            } catch (IOException | RuntimeException ignored) {
                // stream closed
            } finally {
                p.destroy();
            }
        });
        return p::destroy;
    }

    @Override
    public Optional<ContainerInfo> inspect(String name) {
        ProcessRunner.Result r = ProcessRunner.run(List.of(docker(), "inspect", "--type", "container", name), null, SHORT);
        if (!r.ok()) return Optional.empty();
        try {
            JsonNode arr = mapper.readTree(r.output());
            if (!arr.isArray() || arr.isEmpty()) return Optional.empty();
            JsonNode c = arr.get(0);
            JsonNode state = c.path("State");
            String ip = null;
            var nets = c.path("NetworkSettings").path("Networks");
            if (nets.has(props.dockerNetwork())) ip = nets.path(props.dockerNetwork()).path("IPAddress").asText(null);
            return Optional.of(new ContainerInfo(c.path("Id").asText(), c.path("Name").asText().replaceFirst("^/", ""),
                    c.path("Config").path("Image").asText(), state.path("Status").asText(), state.path("Running").asBoolean(),
                    c.path("RestartCount").asInt(), state.path("ExitCode").asInt(), state.path("StartedAt").asText(null),
                    state.path("FinishedAt").asText(null), state.path("Health").path("Status").asText(null), ip));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ContainerStats> stats(String name) {
        ProcessRunner.Result r = ProcessRunner.run(List.of(docker(), "stats", "--no-stream", "--format", "{{json .}}", name), null, SHORT);
        if (!r.ok()) return Optional.empty();
        return r.output().lines().map(this::parseStats).filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public Map<String, ContainerStats> statsAll() {
        Map<String, ContainerStats> out = new HashMap<>();
        List<String> names = listContainers("dev.shipyard.managed", "true");
        if (names.isEmpty()) return out;
        List<String> cmd = new ArrayList<>(List.of(docker(), "stats", "--no-stream", "--format", "{{json .}}"));
        cmd.addAll(names);
        ProcessRunner.Result r = ProcessRunner.run(cmd, null, SHORT);
        r.output().lines().map(this::parseStats).filter(java.util.Objects::nonNull).forEach(s -> out.put(s.name(), s));
        return out;
    }

    @Override
    public ExecResult exec(String name, List<String> command) {
        List<String> cmd = new ArrayList<>(List.of(docker(), "exec", name));
        cmd.addAll(command);
        ProcessRunner.Result r = ProcessRunner.run(cmd, null, SHORT);
        return new ExecResult(r.exitCode(), r.output());
    }

    @Override
    public ExecResult exec(String name, List<String> command, Duration timeout) {
        List<String> cmd = new ArrayList<>(List.of(docker(), "exec", name));
        cmd.addAll(command);
        try {
            ProcessRunner.Result r = ProcessRunner.run(cmd, null, timeout);
            return new ExecResult(r.exitCode(), r.output());
        } catch (ProcessRunner.CommandException e) {
            return new ExecResult(-1, e.getMessage());
        }
    }

    @Override
    public void removeImage(String tag) {
        ProcessRunner.run(List.of(docker(), "image", "rm", tag), null, SHORT);
    }

    @Override
    public String networkSubnet(String network) {
        ProcessRunner.Result r = ProcessRunner.run(List.of(docker(), "network", "inspect", network,
                "--format", "{{range .IPAM.Config}}{{.Subnet}} {{end}}"), null, SHORT);
        if (!r.ok()) return null;
        String first = r.output().strip().split("\\s+")[0];
        return first.isEmpty() ? null : first;
    }

    @Override
    public void ensureVolume(String name, Map<String, String> labels) {
        if (ProcessRunner.run(List.of(docker(), "volume", "inspect", name), null, SHORT).ok()) return;
        List<String> cmd = new ArrayList<>(List.of(docker(), "volume", "create"));
        labels.forEach((k, v) -> cmd.addAll(List.of("--label", k + "=" + v)));
        cmd.add(name);
        ProcessRunner.check(cmd, null, Map.of(), SHORT);
        log.info("Created docker volume {}", name);
    }

    @Override
    public List<String> listVolumes(String label, String value) {
        ProcessRunner.Result r = ProcessRunner.run(List.of(docker(), "volume", "ls", "--format", "{{.Name}}", "--filter", "label=" + label + "=" + value),
                null, SHORT);
        if (!r.ok()) return List.of();
        return r.output().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void removeVolume(String name) {
        ProcessRunner.Result r = ProcessRunner.run(List.of(docker(), "volume", "rm", name), null, SHORT);
        if (!r.ok()) log.warn("Could not remove docker volume {}: {}", name, r.output().strip());
    }

    @Override
    public List<String> listContainers(String label, String value) {
        ProcessRunner.Result r = ProcessRunner.run(List.of(docker(), "ps", "--format", "{{.Names}}", "--filter", "label=" + label + "=" + value),
                null, SHORT);
        if (!r.ok()) return List.of();
        return r.output().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    // ------------------------------------------------------------------ helpers

    private String docker() {
        return props.dockerBinary();
    }

    private static String restartPolicy(String p) {
        if (p == null) return "unless-stopped";
        return switch (p) {
            case "always", "unless-stopped", "no" -> p;
            default -> p.startsWith("on-failure") ? p : "unless-stopped";
        };
    }

    private static Path writeEnvFile(Map<String, String> env) {
        try {
            Path f = Files.createTempFile("shipyard-env", ".env",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            StringBuilder sb = new StringBuilder();
            env.forEach((k, v) -> {
                // env-file format is one KEY=VALUE per line; newlines cannot be represented.
                sb.append(k).append('=').append(v == null ? "" : v.replace("\n", "\\n")).append('\n');
            });
            Files.writeString(f, sb.toString());
            return f;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    ContainerStats parseStats(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            String[] mem = n.path("MemUsage").asText("0B / 0B").split("/");
            String[] net = n.path("NetIO").asText("0B / 0B").split("/");
            String[] blk = n.path("BlockIO").asText("0B / 0B").split("/");
            return new ContainerStats(n.path("Name").asText(), percent(n.path("CPUPerc").asText("0%")),
                    parseSize(mem[0]), mem.length > 1 ? parseSize(mem[1]) : 0,
                    parseSize(net[0]), net.length > 1 ? parseSize(net[1]) : 0,
                    parseSize(blk[0]), blk.length > 1 ? parseSize(blk[1]) : 0,
                    n.path("PIDs").asInt(0));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static double percent(String s) {
        try {
            return Double.parseDouble(s.replace("%", "").strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses docker's human sizes ("312MiB", "1.2kB", "1GiB"). */
    static long parseSize(String s) {
        Matcher m = SIZE.matcher(s.strip());
        if (!m.find()) return 0;
        double v = Double.parseDouble(m.group(1));
        String unit = m.group(2) == null ? "B" : m.group(2).toUpperCase(Locale.ROOT);
        boolean binary = unit.contains("I");
        double base = binary ? 1024 : 1000;
        int pow = switch (unit.charAt(0)) {
            case 'K' -> 1;
            case 'M' -> 2;
            case 'G' -> 3;
            case 'T' -> 4;
            case 'P' -> 5;
            default -> 0;
        };
        return (long) (v * Math.pow(base, pow));
    }
}
