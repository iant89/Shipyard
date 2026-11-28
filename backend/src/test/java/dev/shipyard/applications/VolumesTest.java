package dev.shipyard.applications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shipyard.common.BadRequestException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class VolumesTest {
    @Test
    void normalisesPaths() {
        assertThat(Volumes.normalize(Arrays.asList(" /data/ ", "", null, "/data", "/var/lib/app"))).containsExactly("/data", "/var/lib/app");
        assertThat(Volumes.normalize(null)).isEmpty();
    }

    @Test
    void rejectsUnsafePaths() {
        for (String bad : List.of("data", "/", "/a/../b", "/x:/y", "/a b", "/a,b")) {
            assertThatThrownBy(() -> Volumes.normalize(List.of(bad))).as(bad).isInstanceOf(BadRequestException.class);
        }
        assertThatThrownBy(() -> Volumes.normalize(List.of("/a-b", "/a/b"))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void namesVolumesAfterSlugAndPath() {
        assertThat(Volumes.volumeName("clawrouter", "/data")).isEqualTo("shipyard-clawrouter-data");
        assertThat(Volumes.volumeName("api", "/var/lib/App Data")).isEqualTo("shipyard-api-var-lib-app-data");
    }

    @Test
    void roundTripsStorage() {
        List<String> paths = List.of("/data", "/cache");
        assertThat(Volumes.parse(Volumes.serialize(paths))).isEqualTo(paths);
        assertThat(Volumes.parse("")).isEmpty();
    }
}
