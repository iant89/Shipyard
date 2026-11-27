package dev.shipyard.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs external commands (git, docker) with streaming output, timeouts and
 * cooperative cancellation. Commands are always passed as argument lists,
 * never through a shell.
 */
public final class ProcessRunner {

    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    public static final class CommandException extends RuntimeException {
        private final int exitCode;

        public CommandException(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    private ProcessRunner() {}

    public static Result run(List<String> command, Path workDir, Map<String, String> env, Duration timeout,
                             Consumer<String> lineConsumer, CancellationToken cancel) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) pb.directory(workDir.toFile());
        if (env != null) pb.environment().putAll(env);
        // Never prompt for credentials.
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new CommandException("Could not start " + command.get(0) + ": " + e.getMessage(), -1);
        }
        if (cancel != null) cancel.onCancel(p::destroy);
        StringBuilder captured = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (captured.length() < 1_000_000) captured.append(line).append('\n');
                    if (lineConsumer != null) lineConsumer.accept(line);
                }
            } catch (IOException ignored) {
                // process ended
            }
        });
        try {
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new CommandException(command.get(0) + " timed out after " + timeout.toSeconds() + "s", -1);
            }
            reader.join(5_000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CommandException("Interrupted", -1);
        }
        if (cancel != null && cancel.isCancelled()) throw new CancellationToken.CancelledException();
        return new Result(p.exitValue(), captured.toString());
    }

    public static Result run(List<String> command, Path workDir, Duration timeout) {
        return run(command, workDir, null, timeout, null, null);
    }

    /** Runs and throws {@link CommandException} on non-zero exit. */
    public static String check(List<String> command, Path workDir, Map<String, String> env, Duration timeout) {
        Result r = run(command, workDir, env, timeout, null, null);
        if (!r.ok()) {
            String out = r.output().strip();
            if (out.length() > 2000) out = out.substring(out.length() - 2000);
            throw new CommandException(String.join(" ", redact(command)) + " failed (exit " + r.exitCode() + "): " + out, r.exitCode());
        }
        return r.output();
    }

    static List<String> redact(List<String> command) {
        List<String> out = new ArrayList<>(command.size());
        for (String c : command) out.add(c.replaceAll("://[^/@\\s]+@", "://***@"));
        return out;
    }
}
