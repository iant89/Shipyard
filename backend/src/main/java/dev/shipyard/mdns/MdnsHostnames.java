package dev.shipyard.mdns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.storage.StorageLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tells the mDNS responder ({@link MdnsResponder}, the {@code mdns} compose
 * service on the host network) which .local hostnames to publish: those of
 * running applications. Shipyard writes {@code <data>/mdns/hostnames}; the
 * responder picks changes up within two seconds, announces new names and
 * withdraws removed ones.
 */
@Component
public class MdnsHostnames {
    /** The responder's heartbeat, as shown in the UI. */
    public record ResponderStatus(boolean enabled, boolean running, List<String> addresses) {}

    private static final Logger log = LoggerFactory.getLogger(MdnsHostnames.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlatformProperties props;
    private final StorageLayout layout;

    public MdnsHostnames(PlatformProperties props, StorageLayout layout) {
        this.props = props;
        this.layout = layout;
    }

    public boolean enabled() {
        return props.mdns();
    }

    Path dir() {
        return layout.root().resolve("mdns");
    }

    /** mDNS only resolves .local names. */
    public static boolean publishable(String hostname) {
        String h = hostname.toLowerCase(Locale.ROOT);
        return h.endsWith(".local") && h.length() > ".local".length();
    }

    /** Publishes exactly the .local names among these (running applications' hostnames); writes only on change. */
    public synchronized void sync(Iterable<String> hostnames) {
        TreeSet<String> names = new TreeSet<>();
        if (enabled()) for (String h : hostnames) if (publishable(h)) names.add(h.toLowerCase(Locale.ROOT));
        String content = names.isEmpty() ? "" : String.join("\n", names) + "\n";
        Path file = dir().resolve("hostnames");
        try {
            if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(content)) return;
            if (content.isEmpty() && !Files.exists(file)) return;
            StorageLayout.mkdirs(dir());
            Path tmp = dir().resolve(".hostnames.tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("mDNS hostnames: {}", names.isEmpty() ? "none" : names);
        } catch (IOException e) {
            log.warn("Could not write {}: {}", file, e.getMessage());
        }
    }

    /** Running when the responder wrote its heartbeat in the last 30 seconds. */
    public ResponderStatus status() {
        if (!enabled()) return new ResponderStatus(false, false, List.of());
        try {
            JsonNode n = JSON.readTree(dir().resolve("status").toFile());
            boolean fresh = Instant.ofEpochSecond(n.path("at").asLong()).isAfter(Instant.now().minusSeconds(30));
            List<String> addresses = new ArrayList<>();
            n.path("addresses").forEach(a -> addresses.add(a.asText()));
            return new ResponderStatus(true, fresh, addresses);
        } catch (IOException e) {
            return new ResponderStatus(true, false, List.of());
        }
    }
}
