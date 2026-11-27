package dev.shipyard.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitHubWebhookProviderTest {
    private final GitHubWebhookProvider provider = new GitHubWebhookProvider();

    @Test
    void verifiesKnownGitHubSignature() {
        // Example from GitHub's documentation ("Validating webhook deliveries").
        byte[] body = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        String sig = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";
        assertThat(provider.verifySignature(body, Map.of("x-hub-signature-256", sig), "It's a Secret to Everybody")).isTrue();
        assertThat(GitHubWebhookProvider.sign("It's a Secret to Everybody", body)).isEqualTo(sig);
    }

    @Test
    void rejectsWrongOrMissingSignatures() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String good = GitHubWebhookProvider.sign("secret", body);
        assertThat(provider.verifySignature(body, Map.of("x-hub-signature-256", good), "other")).isFalse();
        assertThat(provider.verifySignature(body, Map.of(), "secret")).isFalse();
        assertThat(provider.verifySignature(body, Map.of("x-hub-signature-256", "sha256=zz"), "secret")).isFalse();
        assertThat(provider.verifySignature(body, Map.of("x-hub-signature", "sha1=abc"), "secret")).isFalse();
        // Signature must cover the exact raw bytes
        byte[] altered = "{ }".getBytes(StandardCharsets.UTF_8);
        assertThat(provider.verifySignature(altered, Map.of("x-hub-signature-256", good), "secret")).isFalse();
    }

    @Test
    void parsesPushEvent() {
        String json = """
                {"ref":"refs/heads/main","before":"aaa","after":"8f3c21a0000000000000000000000000000000ff",
                 "repository":{"full_name":"iant89/MyApp"},
                 "head_commit":{"message":"Fix bug","timestamp":"2026-11-27T09:14:32Z","author":{"name":"Ian","username":"iant89"}}}""";
        ParsedEvent e = provider.parseEvent(json.getBytes(StandardCharsets.UTF_8),
                Map.of("x-github-event", "push", "x-github-delivery", "d-1"));
        assertThat(e.eventType()).isEqualTo("push");
        assertThat(e.deliveryId()).isEqualTo("d-1");
        assertThat(e.repository()).isEqualTo("iant89/MyApp");
        assertThat(e.branch()).isEqualTo("main");
        assertThat(e.commitSha()).startsWith("8f3c21a");
        assertThat(e.previousSha()).isEqualTo("aaa");
        assertThat(e.author()).isEqualTo("iant89");
        assertThat(e.commitMessage()).isEqualTo("Fix bug");
        assertThat(e.branchDeleted()).isFalse();
    }

    @Test
    void detectsTagsAndDeletesAndMalformed() {
        ParsedEvent tag = provider.parseEvent("{\"ref\":\"refs/tags/v1\",\"after\":\"abc\"}".getBytes(), Map.of("x-github-event", "push"));
        assertThat(tag.branch()).isNull();
        ParsedEvent del = provider.parseEvent(("{\"ref\":\"refs/heads/main\",\"deleted\":true,\"after\":\"" + "0".repeat(40) + "\"}").getBytes(),
                Map.of("x-github-event", "push"));
        assertThat(del.branchDeleted()).isTrue();
        assertThatThrownBy(() -> provider.parseEvent("not json".getBytes(), Map.of("x-github-event", "push")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
