package dev.shipyard.domains;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shipyard.config.PlatformProperties;
import org.junit.jupiter.api.Test;

class PublicUrlsTest {
    private static PublicUrls withPort(Integer port) {
        return new PublicUrls(new PlatformProperties(null, null, null, null, null, null, null, null, false, null, null, 0, 0, 0,
                false, null, null, null, port, false, null));
    }

    @Test
    void recognisesLocalHostnames() {
        for (String h : new String[] {"devstick.local", "localhost", "app.localhost", "nas.lan", "box.home", "svc.internal",
                "pi.home.arpa", "devstick", "192.168.1.20", "host.localdomain"}) {
            assertThat(PublicUrls.isLocalHostname(h)).as(h).isTrue();
        }
        for (String h : new String[] {"example.com", "app.example.com", "local.example.com", "my-local.io"}) {
            assertThat(PublicUrls.isLocalHostname(h)).as(h).isFalse();
        }
    }

    @Test
    void addsTheHttpPortToLocalHostnamesOnly() {
        PublicUrls urls = withPort(8081);
        assertThat(urls.url("devstick.local", false)).isEqualTo("http://devstick.local:8081");
        assertThat(urls.url("192.168.1.20", false)).isEqualTo("http://192.168.1.20:8081");
        assertThat(urls.url("app.example.com", false)).isEqualTo("http://app.example.com");
        assertThat(urls.url("devstick.local", true)).isEqualTo("https://devstick.local");
    }

    @Test
    void omitsPort80() {
        assertThat(withPort(80).url("devstick.local", false)).isEqualTo("http://devstick.local");
    }
}
