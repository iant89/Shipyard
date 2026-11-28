package dev.shipyard.domains;

import dev.shipyard.common.ProcessRunner;
import dev.shipyard.config.PlatformProperties;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Builds the URL an application is reached at. Local hostnames (devstick.local,
 * localhost, LAN names, IP addresses) are reached directly on this server, so
 * when Caddy's HTTP port is published on a host port other than 80, their plain
 * HTTP URLs carry that port: http://devstick.local:8081. Public domains are left
 * without a port, since a router usually forwards port 80 to the server.
 */
@Component
public class PublicUrls {
    private static final List<String> LOCAL_SUFFIXES = List.of(".local", ".localhost", ".lan", ".home", ".internal", ".home.arpa", ".localdomain");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern PORT = Pattern.compile(":(\\d+)\\s*$", Pattern.MULTILINE);
    private static final Duration CACHE = Duration.ofSeconds(60);

    private final PlatformProperties props;
    private volatile int detectedPort = -1;
    private volatile long detectedAt;

    public PublicUrls(PlatformProperties props) {
        this.props = props;
    }

    /** Whether a hostname names this server on the local network rather than a public domain. */
    public static boolean isLocalHostname(String hostname) {
        String h = hostname.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || !h.contains(".") || IPV4.matcher(h).matches()) return true;
        return LOCAL_SUFFIXES.stream().anyMatch(h::endsWith);
    }

    public String url(String hostname, boolean tls) {
        if (tls) return "https://" + hostname;
        int port = httpPort();
        return "http://" + hostname + (port != 80 && isLocalHostname(hostname) ? ":" + port : "");
    }

    /**
     * The host port Caddy's HTTP listener is published on: SHIPYARD_HTTP_PORT when
     * set, otherwise read from the Caddy container (cached for a minute), else 80.
     */
    public int httpPort() {
        if (props.httpPort() != null && props.httpPort() > 0) return props.httpPort();
        long now = System.nanoTime();
        if (detectedPort > 0 && now - detectedAt < CACHE.toNanos()) return detectedPort;
        int port = detect();
        detectedPort = port;
        detectedAt = now;
        return port;
    }

    private int detect() {
        try {
            ProcessRunner.Result ps = ProcessRunner.run(List.of(props.dockerBinary(), "ps", "-q",
                    "--filter", "label=com.docker.compose.service=caddy", "--filter", "network=" + props.dockerNetwork()), null, Duration.ofSeconds(5));
            if (!ps.ok() || ps.output().isBlank()) return 80;
            String id = ps.output().strip().split("\\s+")[0];
            ProcessRunner.Result port = ProcessRunner.run(List.of(props.dockerBinary(), "port", id, "80/tcp"), null, Duration.ofSeconds(5));
            if (!port.ok()) return 80;
            Matcher m = PORT.matcher(port.output());
            return m.find() ? Integer.parseInt(m.group(1)) : 80;
        } catch (RuntimeException e) {
            return 80;
        }
    }
}
