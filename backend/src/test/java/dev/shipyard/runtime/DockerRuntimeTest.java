package dev.shipyard.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DockerRuntimeTest {
    @Test
    void parsesDockerSizes() {
        assertThat(DockerRuntime.parseSize("0B")).isZero();
        assertThat(DockerRuntime.parseSize("1.5kB")).isEqualTo(1500);
        assertThat(DockerRuntime.parseSize("312MiB")).isEqualTo(312L * 1024 * 1024);
        assertThat(DockerRuntime.parseSize("1GiB")).isEqualTo(1024L * 1024 * 1024);
    }
}
