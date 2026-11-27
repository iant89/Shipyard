package dev.shipyard.webhooks;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.audit.AuditActions;
import dev.shipyard.audit.AuditService;
import dev.shipyard.common.Ids;
import dev.shipyard.common.NotFoundException;
import dev.shipyard.common.Time;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.deployments.Deployment;
import dev.shipyard.deployments.DeploymentRepository;
import dev.shipyard.deployments.DeploymentService;
import dev.shipyard.deployments.DeploymentSource;
import dev.shipyard.events.EventBus;
import dev.shipyard.events.EventTypes;
import dev.shipyard.repositories.GitRepository;
import dev.shipyard.repositories.GitRepositoryStore;
import dev.shipyard.repositories.RepositoryManager;
import dev.shipyard.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates, records and routes incoming webhooks. Never builds: valid push
 * events become queued deployments and the request returns immediately.
 */
@Service
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    public enum Outcome { ACCEPTED, IGNORED, DUPLICATE, UNAUTHORIZED, NOT_FOUND, MALFORMED }

    public record Result(Outcome outcome, String eventId, String status, String message, String deploymentId) {}

    public record WebhookView(String applicationId, String provider, String endpointUrl, String contentType, List<String> events,
                              boolean automaticDeployment, boolean deployOnPush, String branch, String repository, String status,
                              WebhookEvent lastEvent, Deployment lastDeployment) {}

    private final Map<String, WebhookProvider> providers = new LinkedHashMap<>();
    private final WebhookRepository webhooks;
    private final WebhookEventStore eventStore;
    private final ApplicationRepository applications;
    private final GitRepositoryStore repositories;
    private final DeploymentRepository deployments;
    private final DeploymentService deploymentService;
    private final SecretCipher cipher;
    private final EventBus events;
    private final AuditService audit;
    private final PlatformProperties props;

    public WebhookService(List<WebhookProvider> providerList, WebhookRepository webhooks, WebhookEventStore eventStore,
                          ApplicationRepository applications, GitRepositoryStore repositories, DeploymentRepository deployments,
                          DeploymentService deploymentService, SecretCipher cipher, EventBus events, AuditService audit,
                          PlatformProperties props) {
        providerList.forEach(p -> providers.put(p.id(), p));
        this.webhooks = webhooks;
        this.eventStore = eventStore;
        this.applications = applications;
        this.repositories = repositories;
        this.deployments = deployments;
        this.deploymentService = deploymentService;
        this.cipher = cipher;
        this.events = events;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ receiving

    public Result receive(String providerId, String endpointId, Map<String, String> headers, byte[] rawBody) {
        WebhookProvider provider = providers.get(providerId);
        if (provider == null) return new Result(Outcome.NOT_FOUND, null, null, "Unknown provider", null);
        Optional<Webhook> hook = webhooks.findByEndpoint(providerId, endpointId);
        if (hook.isEmpty()) return new Result(Outcome.NOT_FOUND, null, null, "Unknown webhook", null);
        Webhook w = hook.get();
        return process(provider, w, headers, rawBody, false);
    }

    Result process(WebhookProvider provider, Webhook w, Map<String, String> headers, byte[] rawBody, boolean test) {
        String delivery = headers.get("x-github-delivery");
        String eventType = headers.getOrDefault("x-github-event", "unknown");

        // 1-4: signature over the raw body; invalid signatures are rejected before parsing.
        if (!provider.verifySignature(rawBody, headers, cipher.decrypt(w.secretEnc()))) {
            WebhookEvent e = event(w, provider, eventType, null, null, "FAILED", "Invalid signature", test);
            eventStore.insert(e);
            publish(w.applicationId(), e);
            log.warn("Rejected webhook for application {}: invalid signature", w.applicationId());
            return new Result(Outcome.UNAUTHORIZED, e.id(), e.status(), "Invalid signature", null);
        }

        // Duplicate deliveries (GitHub retries) never create a second deployment.
        if (!test && eventStore.deliveryExists(provider.id(), delivery)) {
            return new Result(Outcome.DUPLICATE, null, "IGNORED", "Duplicate delivery " + delivery, null);
        }

        ParsedEvent parsed;
        try {
            parsed = provider.parseEvent(rawBody, headers);
        } catch (IllegalArgumentException ex) {
            WebhookEvent e = event(w, provider, eventType, test ? null : delivery, null, "FAILED", ex.getMessage(), test);
            eventStore.insert(e);
            publish(w.applicationId(), e);
            return new Result(Outcome.MALFORMED, e.id(), e.status(), ex.getMessage(), null);
        }

        WebhookEvent recorded = event(w, provider, parsed.eventType(), test ? null : parsed.deliveryId(), parsed, "RECEIVED", null, test);
        if (!eventStore.insert(recorded)) {
            return new Result(Outcome.DUPLICATE, null, "IGNORED", "Duplicate delivery " + delivery, null);
        }
        events.publish(EventTypes.WEBHOOK_RECEIVED, w.applicationId(), null, Map.of("eventId", recorded.id(), "type", parsed.eventType()));

        Application app = applications.get(w.applicationId());
        GitRepository repo = repositories.forApplication(app.id());
        String ignore = ignoreReason(app, w, repo, parsed, test);
        if (ignore != null) {
            eventStore.update(recorded.id(), "IGNORED", null, ignore);
            publishProcessed(app.id(), recorded.id(), "IGNORED", ignore);
            return new Result(Outcome.IGNORED, recorded.id(), "IGNORED", ignore, null);
        }

        Deployment d = deploymentService.request(new DeploymentService.DeploymentRequest(app.id(), DeploymentSource.WEBHOOK, null,
                parsed.commitSha(), parsed.branch(), parsed.commitMessage(), parsed.author(), "webhook:" + provider.id().toLowerCase()));
        eventStore.update(recorded.id(), "QUEUED", d.id(), null);
        audit.record(AuditActions.WEBHOOK_TRIGGERED, "APPLICATION", app.id(),
                Map.of("commit", parsed.commitSha(), "deployment", d.number(), "delivery", nz(parsed.deliveryId())));
        publishProcessed(app.id(), recorded.id(), "QUEUED", "Deployment #" + d.number() + " queued");
        return new Result(Outcome.ACCEPTED, recorded.id(), "QUEUED", "Deployment #" + d.number() + " queued", d.id());
    }

    /** Returns why the event does not trigger a deployment, or null if it should. */
    private String ignoreReason(Application app, Webhook w, GitRepository repo, ParsedEvent e, boolean test) {
        if ("ping".equals(e.eventType())) return "Ping received; webhook is configured correctly";
        if (!"push".equals(e.eventType())) return "Event type '" + e.eventType() + "' is not handled";
        String expected = RepositoryManager.fullName(repo.url());
        if (e.repository() == null || !expected.equalsIgnoreCase(e.repository())) {
            return "Unknown repository " + e.repository() + " (expected " + expected + ")";
        }
        if (e.branch() == null) return "Not a branch push";
        if (!e.branch().equals(repo.branch())) return "Push to " + e.branch() + " ignored (deployment branch is " + repo.branch() + ")";
        if (e.branchDeleted() || e.commitSha() == null) return "Branch deletion ignored";
        if (test) return "Test event: signature and configuration valid; no deployment triggered";
        if (!app.autoDeploy()) return "Automatic deployment is disabled";
        if (!w.deployOnPush()) return "Deploy on push is disabled";
        if (deployments.existsActiveForCommit(app.id(), e.commitSha())) return "Commit " + e.commitSha().substring(0, 7) + " is already deployed or queued";
        return null;
    }

    // ------------------------------------------------------------------ management

    public Webhook create(String applicationId) {
        String now = Time.now();
        Webhook w = new Webhook(Ids.uuid(), applicationId, GitHubWebhookProvider.ID, Ids.randomToken(20),
                cipher.encrypt(Ids.randomToken(32)), true, now, now);
        webhooks.insert(w);
        audit.record(AuditActions.WEBHOOK_CREATED, "APPLICATION", applicationId, Map.of());
        return w;
    }

    public WebhookView view(String applicationId) {
        Webhook w = get(applicationId);
        Application app = applications.get(applicationId);
        GitRepository repo = repositories.forApplication(applicationId);
        WebhookEvent last = eventStore.lastForApplication(applicationId).orElse(null);
        Deployment lastDeployment = deployments.findByApplication(applicationId, 50).stream()
                .filter(d -> d.source() == DeploymentSource.WEBHOOK).findFirst().orElse(null);
        String status = !app.autoDeploy() || !w.deployOnPush() ? "DISABLED"
                : last == null ? "WAITING" : "FAILED".equals(last.status()) && "Invalid signature".equals(last.error()) ? "ERROR" : "ACTIVE";
        return new WebhookView(applicationId, w.provider(), endpointUrl(w), "application/json", List.of("push"), app.autoDeploy(),
                w.deployOnPush(), repo.branch(), RepositoryManager.fullName(repo.url()), status, last, lastDeployment);
    }

    public String revealSecret(String applicationId) {
        return cipher.decrypt(get(applicationId).secretEnc());
    }

    public String regenerateSecret(String applicationId) {
        Webhook w = get(applicationId);
        String secret = Ids.randomToken(32);
        webhooks.updateSecret(w.id(), cipher.encrypt(secret));
        audit.record(AuditActions.WEBHOOK_UPDATED, "APPLICATION", applicationId, Map.of("change", "secret regenerated"));
        return secret;
    }

    public void setDeployOnPush(String applicationId, boolean value) {
        webhooks.updateDeployOnPush(get(applicationId).id(), value);
        audit.record(AuditActions.WEBHOOK_UPDATED, "APPLICATION", applicationId, Map.of("deployOnPush", value));
    }

    /**
     * Sends a signed synthetic push event through the real verification and
     * routing path. Records a test event; never deploys.
     */
    public Result test(String applicationId) {
        Webhook w = get(applicationId);
        GitRepository repo = repositories.forApplication(applicationId);
        String body = """
                {"ref":"refs/heads/%s","before":"0000000000000000000000000000000000000001","after":"%s",
                 "repository":{"full_name":"%s"},"pusher":{"name":"shipyard-test"},
                 "head_commit":{"message":"Shipyard webhook test","author":{"name":"shipyard-test"}}}"""
                .formatted(repo.branch(), "0".repeat(39) + "1", RepositoryManager.fullName(repo.url()));
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of(
                "x-github-event", "push",
                "x-github-delivery", "test-" + Ids.randomToken(8),
                "x-hub-signature-256", GitHubWebhookProvider.sign(cipher.decrypt(w.secretEnc()), raw));
        return process(providers.get(w.provider()), w, headers, raw, true);
    }

    public List<WebhookEvent> history(String applicationId, int limit) {
        return eventStore.forApplication(applicationId, limit);
    }

    public String endpointUrl(Webhook w) {
        return props.publicUrlNoSlash() + "/api/webhooks/" + w.provider().toLowerCase() + "/" + w.endpointId();
    }

    private Webhook get(String applicationId) {
        return webhooks.findByApplication(applicationId).orElseThrow(() -> new NotFoundException("Application has no webhook"));
    }

    private static WebhookEvent event(Webhook w, WebhookProvider p, String type, String delivery, ParsedEvent e, String status,
                                      String error, boolean test) {
        return new WebhookEvent(Ids.uuid(), w.applicationId(), p.id(), type, delivery,
                e == null ? null : e.repository(), e == null ? null : e.branch(), e == null ? null : e.commitSha(),
                e == null ? null : e.previousSha(), e == null ? null : e.author(), e == null ? null : e.commitMessage(),
                Time.now(), status, null, error, test);
    }

    private void publish(String applicationId, WebhookEvent e) {
        events.publish(EventTypes.WEBHOOK_RECEIVED, applicationId, null, Map.of("eventId", e.id(), "status", e.status()));
    }

    private void publishProcessed(String applicationId, String eventId, String status, String message) {
        events.publish(EventTypes.WEBHOOK_PROCESSED, applicationId, null, Map.of("eventId", eventId, "status", status, "message", message));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
