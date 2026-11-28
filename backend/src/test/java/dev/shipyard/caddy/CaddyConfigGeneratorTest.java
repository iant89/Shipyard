package dev.shipyard.caddy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CaddyConfigGeneratorTest {
    @Test
    void rendersStaticProxyAndUnavailableSites() {
        String cfg = CaddyConfigGenerator.generate("unix//run/caddy-admin/admin.sock", true, "ops@example.com", "platform.example.com",
                "shipyard:8080", List.of(
                        new CaddyRoute("site", List.of(new CaddyRoute.Host("example.com", true), new CaddyRoute.Host("www.example.com", true)),
                                "/var/lib/shipyard/deployments/a/004/dist", null, null),
                        new CaddyRoute("api", List.of(new CaddyRoute.Host("api.lan", false)), null, "shipyard-api-web-7:8080", null),
                        new CaddyRoute("off", List.of(new CaddyRoute.Host("off.example.com", true)), null, null, "Application is stopped"),
                        new CaddyRoute("nohost", List.of(), null, "x:1", null)));
        assertThat(cfg).contains("admin unix//run/caddy-admin/admin.sock");
        assertThat(cfg).contains("email ops@example.com");
        assertThat(cfg).contains("platform.example.com {");
        assertThat(cfg).contains("reverse_proxy shipyard:8080");
        assertThat(cfg).contains("example.com, www.example.com {");
        assertThat(cfg).contains("root * /var/lib/shipyard/deployments/a/004/dist");
        assertThat(cfg).contains("try_files {path} {path}/ /index.html");
        assertThat(cfg).contains("http://api.lan {");
        assertThat(cfg).contains("reverse_proxy shipyard-api-web-7:8080");
        assertThat(cfg).contains("respond \"Application is stopped\" 503");
        assertThat(cfg).doesNotContain("x:1");
        assertThat(cfg).doesNotContain("auto_https off");
        // Server-Sent Events must not be compressed, or Caddy buffers them.
        assertThat(cfg).contains("encode zstd gzip {").contains("header Content-Type text/html*");
        assertThat(cfg).doesNotContain("event-stream").doesNotContain("text/*");
    }

    @Test
    void rendersMaintenancePageAsUncachedHtml503() {
        String cfg = CaddyConfigGenerator.generate(null, false, null, null, null,
                List.of(CaddyRoute.maintenance("updating", List.of(new CaddyRoute.Host("updating.example.com", false)))));

        assertThat(cfg).contains("http://updating.example.com {")
                .contains("header Content-Type \"text/html; charset=utf-8\"")
                .contains("header Cache-Control \"no-store\"")
                .contains("respond \"<!doctype html>")
                .contains("<h1>We'll be back shortly</h1>")
                .contains("This application is currently being updated, please try again in a few minutes.")
                .contains(" 503");
    }

    @Test
    void httpsDisabledServesEverySiteOverPlainHttp() {
        String cfg = CaddyConfigGenerator.generate("unix//run/caddy-admin/admin.sock", false, "ops@example.com", "platform.example.com",
                "shipyard:8080", List.of(
                        new CaddyRoute("site", List.of(new CaddyRoute.Host("example.com", true)), null, "site-web-1:3000", null),
                        new CaddyRoute("lan", List.of(new CaddyRoute.Host("app.lan", false)), null, "lan-web-1:3000", null)));
        // No ACME, no certificates, no 443 listener anywhere.
        assertThat(cfg).contains("\tauto_https off");
        assertThat(cfg).doesNotContain("email ops@example.com");
        assertThat(cfg).doesNotContain("https_port");
        assertThat(cfg).contains("http://platform.example.com {");
        assertThat(cfg).contains("http://example.com {");
        assertThat(cfg).contains("http://app.lan {");

        // When no platform domain is set, :80 stays open and proxies to the platform UI.
        String fallback = CaddyConfigGenerator.generate("unix//run/caddy-admin/admin.sock", false, "", "",
                "shipyard:8080", List.of());
        assertThat(fallback).contains(":80 {\n\timport shipyard_common\n\treverse_proxy shipyard:8080");
    }
}
