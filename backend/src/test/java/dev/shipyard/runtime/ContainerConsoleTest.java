package dev.shipyard.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.shipyard.common.BadRequestException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerConsoleTest {
    @TempDir Path tmp;

    /** Runs the generated script with the real sh, as docker exec would. */
    private String sh(String script) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", script).directory(tmp.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(10, TimeUnit.SECONDS);
        return out + "\u0000" + p.exitValue();
    }

    private ContainerConsole.Result runReal(String command, String cwd) throws Exception {
        String[] r = sh(ContainerConsole.script(command, cwd)).split("\u0000");
        return ContainerConsole.parse(Integer.parseInt(r[1]), r[0], cwd, 1);
    }

    @Test
    void keepsOutputExitCodeAndDirectory() throws Exception {
        Files.writeString(tmp.resolve("setup-token"), "abc123\n");
        ContainerConsole.Result r = runReal("cat setup-token", tmp.toString());
        assertThat(r.output()).isEqualTo("abc123\n");
        assertThat(r.exitCode()).isZero();
        assertThat(r.cwd()).isEqualTo(tmp.toRealPath().toString());

        r = runReal("mkdir -p 'sub dir' && cd 'sub dir'", tmp.toString());
        assertThat(r.cwd()).endsWith("/sub dir");
        r = runReal("pwd; exit 0", r.cwd());   // explicit exit: directory unchanged, output kept
        assertThat(r.output()).contains("sub dir");

        r = runReal("echo oops >&2; false", tmp.toString());
        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.output()).isEqualTo("oops\n");
    }

    @Test
    void quotesTheDirectorySafely() throws Exception {
        ContainerConsole.Result r = runReal("pwd", "/nonexistent'; echo pwned; '");
        assertThat(r.output()).doesNotContain("pwned").isEqualTo("/\n");
    }

    @Test
    void reportsMissingShellAndTimeouts() {
        ContainerRuntime rt = mock(ContainerRuntime.class);
        when(rt.exec(eq("c"), any(), any(Duration.class)))
                .thenReturn(new ContainerRuntime.ExecResult(127, "OCI runtime exec failed: exec: \"sh\": executable file not found in $PATH\n"));
        ContainerConsole console = new ContainerConsole(rt);
        assertThat(console.run("c", "ls", "/").output()).contains("has no /bin/sh");

        when(rt.exec(eq("c"), any(), any(Duration.class))).thenReturn(new ContainerRuntime.ExecResult(-1, "docker timed out after 60s"));
        assertThat(console.run("c", "sleep 100", "/app").timedOut()).isTrue();
        assertThat(console.run("c", "sleep 100", "/app").cwd()).isEqualTo("/app");

        assertThatThrownBy(() -> console.run("c", "  ", "/")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void truncatesHugeOutput() {
        String big = "x".repeat(ContainerConsole.MAX_OUTPUT + 10);
        ContainerConsole.Result r = ContainerConsole.parse(0, big + "\n" + ContainerConsole.CWD_MARKER + "/\n", "/", 1);
        assertThat(r.truncated()).isTrue();
        assertThat(r.output()).hasSize(ContainerConsole.MAX_OUTPUT);
        assertThat(List.of(r.cwd())).containsExactly("/");
    }
}
