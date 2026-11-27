package dev.shipyard.deployments;

import dev.shipyard.common.CancellationToken;
import dev.shipyard.runtime.ContainerInfo;
import dev.shipyard.runtime.ContainerRuntime;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Decides whether a new deployment is healthy. Supported checks: HTTP (any
 * 2xx/3xx response on the configured path), TCP (port accepts connections),
 * PROCESS (container stays running) and NONE.
 */
@Component
public class HealthChecker {
    public record Target(String containerName, int port, String type, String path, int timeoutSeconds) {}

    private static final Duration INTERVAL = Duration.ofSeconds(2);
    private static final Duration PROCESS_STABLE = Duration.ofSeconds(10);

    private final ContainerRuntime runtime;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(java.net.ProxySelector.of(null))
            .build();

    public HealthChecker(ContainerRuntime runtime) {
        this.runtime = runtime;
    }

    /** Static deployments are healthy when the artifact contains something servable. */
    public boolean checkStatic(Path dist, Consumer<String> log) {
        boolean ok = Files.isRegularFile(dist.resolve("index.html"));
        if (!ok) {
            try (var s = Files.list(dist)) {
                ok = s.findAny().isPresent();
                if (ok) log.accept("Warning: no index.html in the artifact root");
            } catch (IOException e) {
                ok = false;
            }
        }
        log.accept(ok ? "Static artifact check passed" : "Static artifact is empty");
        return ok;
    }

    public boolean check(Target t, Consumer<String> log, CancellationToken cancel) {
        String type = t.type() == null ? "HTTP" : t.type().toUpperCase();
        long deadline = System.nanoTime() + Duration.ofSeconds(Math.max(5, t.timeoutSeconds())).toNanos();
        long runningSince = -1;
        String lastError = null;
        log.accept("Health check: " + type + (type.equals("HTTP") ? " " + t.path() : "") + " on port " + t.port()
                + " (timeout " + t.timeoutSeconds() + "s)");
        while (System.nanoTime() < deadline) {
            cancel.throwIfCancelled();
            Optional<ContainerInfo> info = runtime.inspect(t.containerName());
            if (info.isEmpty()) {
                log.accept("Container disappeared");
                return false;
            }
            ContainerInfo c = info.get();
            if (!c.running()) {
                log.accept("Container is not running (status " + c.status() + ", exit code " + c.exitCode() + ")");
                if ("exited".equals(c.status()) || "dead".equals(c.status())) return false;
            } else {
                if (runningSince < 0) runningSince = System.nanoTime();
                String host = c.ipAddress() != null && !c.ipAddress().isBlank() ? c.ipAddress() : t.containerName();
                switch (type) {
                    case "NONE" -> {
                        log.accept("Health check disabled; container is running");
                        return true;
                    }
                    case "PROCESS" -> {
                        if (System.nanoTime() - runningSince > PROCESS_STABLE.toNanos() && c.restartCount() == 0) {
                            log.accept("Process has been running for " + PROCESS_STABLE.toSeconds() + "s");
                            return true;
                        }
                    }
                    case "TCP" -> {
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(host, t.port()), 2000);
                            log.accept("TCP port " + t.port() + " is accepting connections");
                            return true;
                        } catch (IOException e) {
                            lastError = e.getMessage();
                        }
                    }
                    default -> {
                        String path = t.path() == null || t.path().isBlank() ? "/" : (t.path().startsWith("/") ? t.path() : "/" + t.path());
                        try {
                            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create("http://" + host + ":" + t.port() + path))
                                    .timeout(Duration.ofSeconds(5)).header("User-Agent", "Shipyard-HealthCheck").GET().build(),
                                    HttpResponse.BodyHandlers.discarding());
                            if (r.statusCode() >= 200 && r.statusCode() < 400) {
                                log.accept("HTTP " + r.statusCode() + " from " + path);
                                return true;
                            }
                            lastError = "HTTP " + r.statusCode();
                        } catch (IOException e) {
                            lastError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                }
            }
            sleep();
        }
        log.accept("Health check timed out" + (lastError != null ? " (last error: " + lastError + ")" : ""));
        return false;
    }

    private static void sleep() {
        try {
            Thread.sleep(INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
