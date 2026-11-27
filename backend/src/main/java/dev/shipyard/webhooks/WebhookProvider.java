package dev.shipyard.webhooks;

import java.util.Map;

/** Abstraction over Git hosting webhooks (GitHub now; GitLab, Gitea, Bitbucket later). */
public interface WebhookProvider {

    /** Provider id used in URLs and storage, e.g. GITHUB. */
    String id();

    /** Verifies the signature over the exact raw request body. */
    boolean verifySignature(byte[] rawBody, Map<String, String> headers, String secret);

    /** Parses the event. Throws {@link IllegalArgumentException} for malformed payloads. */
    ParsedEvent parseEvent(byte[] rawBody, Map<String, String> headers);

    default String extractRepository(ParsedEvent e) { return e.repository(); }
    default String extractBranch(ParsedEvent e) { return e.branch(); }
    default String extractCommit(ParsedEvent e) { return e.commitSha(); }
    default String extractDeliveryId(ParsedEvent e) { return e.deliveryId(); }
}
