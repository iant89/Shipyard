package dev.shipyard.webhooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * GitHub webhooks. Signatures are HMAC-SHA256 of the raw body, sent as
 * {@code X-Hub-Signature-256: sha256=<hex>} and compared in constant time.
 */
@Component
public class GitHubWebhookProvider implements WebhookProvider {
    public static final String ID = "GITHUB";
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean verifySignature(byte[] rawBody, Map<String, String> headers, String secret) {
        String header = headers.get("x-hub-signature-256");
        if (header == null || !header.startsWith("sha256=") || secret == null || secret.isEmpty()) return false;
        byte[] expected = hmac(secret, rawBody);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(header.substring("sha256=".length()).strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }

    public static String sign(String secret, byte[] body) {
        return "sha256=" + HexFormat.of().formatHex(hmac(secret, body));
    }

    static byte[] hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ParsedEvent parseEvent(byte[] rawBody, Map<String, String> headers) {
        String type = headers.getOrDefault("x-github-event", "unknown");
        String delivery = headers.get("x-github-delivery");
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed JSON payload");
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Malformed payload");
        String repo = root.path("repository").path("full_name").asText(null);
        if (!"push".equals(type)) {
            return new ParsedEvent(type, delivery, repo, null, null, null, null, null, null, false);
        }
        String ref = root.path("ref").asText("");
        String branch = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : null;
        String after = root.path("after").asText(null);
        JsonNode head = root.path("head_commit");
        String author = head.path("author").path("username").asText(head.path("author").path("name").asText(
                root.path("pusher").path("name").asText(null)));
        boolean deleted = root.path("deleted").asBoolean(false) || (after != null && after.matches("0+"));
        return new ParsedEvent(type, delivery, repo, branch, after, root.path("before").asText(null), author,
                head.path("message").asText(null), head.path("timestamp").asText(null), deleted);
    }
}
