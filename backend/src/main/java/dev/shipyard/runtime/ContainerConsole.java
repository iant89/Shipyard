package dev.shipyard.runtime;

import dev.shipyard.common.BadRequestException;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Runs one shell command at a time inside an application's container (the
 * Console tab). Not an interactive terminal: each command runs with
 * {@code sh -c} in a fresh shell, so programs that need a TTY or keyboard input
 * (editors, pagers, top) do not work. The working directory is carried between
 * commands by the caller, so {@code cd} behaves as expected.
 */
@Component
public class ContainerConsole {
    public static final Duration TIMEOUT = Duration.ofSeconds(60);
    public static final int MAX_COMMAND = 4_000;
    public static final int MAX_OUTPUT = 256 * 1024;
    /** Marks the line carrying the shell's final working directory (never shown to the user). */
    static final String CWD_MARKER = "__SHIPYARD_CWD_7f3a__";

    public record Result(int exitCode, String output, String cwd, boolean truncated, boolean timedOut, long durationMs) {}

    private final ContainerRuntime runtime;

    public ContainerConsole(ContainerRuntime runtime) {
        this.runtime = runtime;
    }

    public Result run(String container, String command, String cwd) {
        if (command == null || command.isBlank()) throw new BadRequestException("Enter a command");
        if (command.length() > MAX_COMMAND) throw new BadRequestException("Command is too long (max " + MAX_COMMAND + " characters)");
        long start = System.nanoTime();
        ContainerRuntime.ExecResult r = runtime.exec(container, List.of("sh", "-c", script(command, cwd)), TIMEOUT);
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        if (r.exitCode() == -1 && r.output() != null && r.output().contains("timed out")) {
            return new Result(-1, "Command timed out after " + TIMEOUT.toSeconds() + " s (it may still be running in the container).",
                    cwd, false, true, ms);
        }
        return parse(r.exitCode(), r.output() == null ? "" : r.output(), cwd, ms);
    }

    /**
     * The user's command runs on its own lines (so comments, here-docs and
     * {@code &&} work), after changing to the remembered directory; afterwards
     * the shell prints its directory on a marker line and exits with the
     * command's status.
     */
    static String script(String command, String cwd) {
        String dir = cwd == null || cwd.isBlank() ? "" : "cd " + quote(cwd) + " 2>/dev/null || cd /\n";
        return dir + command + "\n__shipyard_rc=$?\nprintf '\\n" + CWD_MARKER + "%s\\n' \"$PWD\"\nexit $__shipyard_rc\n";
    }

    static Result parse(int exitCode, String raw, String previousCwd, long ms) {
        String output = raw;
        String cwd = previousCwd;
        int i = raw.lastIndexOf(CWD_MARKER);
        if (i >= 0) {
            int end = raw.indexOf('\n', i);
            cwd = raw.substring(i + CWD_MARKER.length(), end < 0 ? raw.length() : end).strip();
            output = raw.substring(0, i);
            if (output.endsWith("\n")) output = output.substring(0, output.length() - 1); // the marker's own leading newline
        } else if (exitCode == 126 || exitCode == 127 || raw.contains("executable file not found")) {
            output = raw + (raw.endsWith("\n") || raw.isEmpty() ? "" : "\n")
                    + "This container has no /bin/sh, so the console cannot run commands in it.";
        }
        boolean truncated = output.length() > MAX_OUTPUT;
        if (truncated) output = output.substring(0, MAX_OUTPUT);
        return new Result(exitCode, output, cwd == null || cwd.isBlank() ? "/" : cwd, truncated, false, ms);
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
