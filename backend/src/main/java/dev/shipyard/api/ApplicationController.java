package dev.shipyard.api;

import dev.shipyard.api.ApplicationViews.ApplicationDetail;
import dev.shipyard.api.ApplicationViews.ApplicationSummary;
import dev.shipyard.api.ApplicationViews.DeploymentSummary;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.applications.ApplicationService;
import dev.shipyard.applications.ServiceRepository;
import dev.shipyard.audit.AuditService;
import dev.shipyard.common.NotFoundException;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.deployments.Deployment;
import dev.shipyard.deployments.DeploymentRepository;
import dev.shipyard.deployments.DeploymentService;
import dev.shipyard.deployments.DeploymentSource;
import dev.shipyard.detection.DetectionResult;
import dev.shipyard.detection.DetectionService;
import dev.shipyard.domains.Domain;
import dev.shipyard.domains.DomainService;
import dev.shipyard.environment.EnvironmentService;
import dev.shipyard.events.EventBus;
import dev.shipyard.logging.LogLine;
import dev.shipyard.logging.LogService;
import dev.shipyard.metrics.MetricSample;
import dev.shipyard.metrics.MetricsService;
import dev.shipyard.repositories.GitRepositoryStore;
import dev.shipyard.repositories.UpdateChecker;
import dev.shipyard.runtime.ContainerInfo;
import dev.shipyard.runtime.ContainerRuntime;
import dev.shipyard.security.SecretMasker;
import dev.shipyard.webhooks.WebhookEvent;
import dev.shipyard.webhooks.WebhookService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    public record DeployRequest(String ref, String source) {}
    public record RollbackRequest(String deploymentId) {}
    public record EnvRequest(String name, String value, boolean secret) {}
    public record DomainRequest(String hostname, Boolean tlsEnabled, Boolean primary) {}
    public record WebhookSettings(Boolean deployOnPush, Boolean automaticDeployment) {}

    private final ApplicationRepository applications;
    private final ApplicationService service;
    private final ApplicationViews views;
    private final DeploymentService deploymentService;
    private final DeploymentRepository deployments;
    private final ServiceRepository services;
    private final GitRepositoryStore repositories;
    private final DomainService domains;
    private final EnvironmentService environment;
    private final WebhookService webhooks;
    private final LogService logs;
    private final MetricsService metrics;
    private final ContainerRuntime runtime;
    private final DetectionService detection;
    private final EventBus events;
    private final AuditService audit;
    private final PlatformProperties props;
    private final UpdateChecker updates;

    public ApplicationController(ApplicationRepository applications, ApplicationService service, ApplicationViews views,
                                 DeploymentService deploymentService, DeploymentRepository deployments, ServiceRepository services,
                                 GitRepositoryStore repositories, DomainService domains, EnvironmentService environment,
                                 WebhookService webhooks, LogService logs, MetricsService metrics, ContainerRuntime runtime,
                                 DetectionService detection, EventBus events, AuditService audit, PlatformProperties props,
                                 UpdateChecker updates) {
        this.applications = applications;
        this.service = service;
        this.views = views;
        this.deploymentService = deploymentService;
        this.deployments = deployments;
        this.services = services;
        this.repositories = repositories;
        this.domains = domains;
        this.environment = environment;
        this.webhooks = webhooks;
        this.logs = logs;
        this.metrics = metrics;
        this.runtime = runtime;
        this.detection = detection;
        this.events = events;
        this.audit = audit;
        this.props = props;
        this.updates = updates;
    }

    // ------------------------------------------------------------------ CRUD

    @GetMapping
    List<ApplicationSummary> list() {
        return applications.findAll().stream().map(views::summary).toList();
    }

    @PostMapping
    ResponseEntity<ApplicationDetail> create(@RequestBody ApplicationService.CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(views.detail(service.create(req)));
    }

    @GetMapping("/{id}")
    ApplicationDetail get(@PathVariable String id) {
        return views.detail(applications.get(id));
    }

    @PutMapping("/{id}")
    ApplicationDetail update(@PathVariable String id, @RequestBody ApplicationService.UpdateRequest req) {
        return views.detail(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ lifecycle

    @PostMapping("/{id}/deploy")
    ResponseEntity<DeploymentSummary> deploy(@PathVariable String id, @RequestBody(required = false) DeployRequest req) {
        applications.get(id);
        DeploymentSource source = req != null && "API".equalsIgnoreCase(req.source()) ? DeploymentSource.API : DeploymentSource.MANUAL;
        Deployment d = deploymentService.request(new DeploymentService.DeploymentRequest(id, source, req == null ? null : req.ref(),
                null, null, null, null, "user"));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApplicationViews.deployment(d));
    }

    @PostMapping("/{id}/restart")
    ResponseEntity<Void> restart(@PathVariable String id) {
        deploymentService.restart(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/stop")
    ResponseEntity<Void> stop(@PathVariable String id) {
        deploymentService.stop(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/start")
    ResponseEntity<Void> start(@PathVariable String id) {
        deploymentService.start(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/rollback")
    ResponseEntity<DeploymentSummary> rollback(@PathVariable String id, @RequestBody(required = false) RollbackRequest req) {
        Deployment target = deploymentService.rollback(id, req == null ? null : req.deploymentId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApplicationViews.deployment(target));
    }

    @PostMapping("/{id}/detect")
    DetectionResult detect(@PathVariable String id) {
        return detection.detectFor(repositories.forApplication(id), services.primary(id));
    }

    /** Whether newer commits than the running one are on the configured branch. Fetches from the remote (cached ~60 s). */
    @GetMapping("/{id}/updates")
    UpdateChecker.UpdateCheck updates(@PathVariable String id, @RequestParam(defaultValue = "false") boolean refresh) {
        return updates.check(id, refresh);
    }

    @GetMapping("/{id}/branches")
    List<String> branches(@PathVariable String id) {
        var repo = repositories.forApplication(id);
        return detection.branchesFor(repo);
    }

    // ------------------------------------------------------------------ deployments

    @GetMapping("/{id}/deployments")
    List<DeploymentSummary> deployments(@PathVariable String id, @RequestParam(defaultValue = "50") int limit) {
        applications.get(id);
        return deploymentService.list(id, Math.min(limit, 500)).stream().map(ApplicationViews::deployment).toList();
    }

    @GetMapping("/{id}/deployments/{deploymentId}")
    DeploymentSummary deployment(@PathVariable String id, @PathVariable String deploymentId) {
        return ApplicationViews.deployment(find(id, deploymentId));
    }

    @PostMapping("/{id}/deployments/{deploymentId}/cancel")
    ResponseEntity<Void> cancel(@PathVariable String id, @PathVariable String deploymentId) {
        deploymentService.cancel(id, find(id, deploymentId).id());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/deployments/{deploymentId}/redeploy")
    ResponseEntity<DeploymentSummary> redeploy(@PathVariable String id, @PathVariable String deploymentId) {
        Deployment old = find(id, deploymentId);
        if (old.commitSha() == null) throw new IllegalStateException("Deployment #" + old.number() + " has no commit to redeploy");
        Deployment d = deploymentService.request(new DeploymentService.DeploymentRequest(id, DeploymentSource.MANUAL, null,
                old.commitSha(), old.branch(), old.commitMessage(), old.commitAuthor(), "user"));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApplicationViews.deployment(d));
    }

    @GetMapping("/{id}/deployments/{deploymentId}/logs")
    Map<String, List<LogLine>> deploymentLogs(@PathVariable String id, @PathVariable String deploymentId,
                                              @RequestParam(defaultValue = "0") long afterBuild,
                                              @RequestParam(defaultValue = "0") long afterDeployment) {
        String resolved = find(id, deploymentId).id();
        Map<String, List<LogLine>> m = new LinkedHashMap<>();
        m.put("build", logs.buildLogs(resolved, afterBuild, 20_000));
        m.put("deployment", logs.deploymentLogs(resolved, afterDeployment, 5_000));
        return m;
    }

    // ------------------------------------------------------------------ runtime logs / metrics

    @GetMapping("/{id}/logs")
    Map<String, Object> logs(@PathVariable String id, @RequestParam(defaultValue = "500") int tail) {
        var app = applications.get(id);
        if (app.currentDeploymentId() == null) return Map.of("lines", List.of(), "container", "");
        Deployment d = deployments.get(app.currentDeploymentId());
        if (d.isStatic() || d.containerName() == null) {
            return Map.of("lines", List.of("Static application: requests are served by Caddy; there is no application process."), "container", "");
        }
        SecretMasker masker = environment.masker(id);
        List<String> lines = runtime.logs(d.containerName(), Math.min(tail, 5000)).stream().map(masker::mask).toList();
        return Map.of("lines", lines, "container", d.containerName());
    }

    /** Live application log stream (SSE). */
    @GetMapping(path = "/{id}/logs/stream", produces = "text/event-stream")
    SseEmitter streamLogs(@PathVariable String id, @RequestParam(defaultValue = "200") int tail) {
        var app = applications.get(id);
        SseEmitter emitter = new SseEmitter(0L);
        if (app.currentDeploymentId() == null) {
            emitter.complete();
            return emitter;
        }
        Deployment d = deployments.get(app.currentDeploymentId());
        if (d.isStatic() || d.containerName() == null) {
            emitter.complete();
            return emitter;
        }
        SecretMasker masker = environment.masker(id);
        AutoCloseable handle = runtime.followLogs(d.containerName(), Math.min(tail, 2000), line -> {
            try {
                emitter.send(SseEmitter.event().name("log").data(masker.mask(line)));
            } catch (IOException | IllegalStateException e) {
                throw new IllegalStateException("client gone", e);
            }
        });
        Runnable close = () -> {
            try { handle.close(); } catch (Exception ignored) { }
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(t -> close.run());
        return emitter;
    }

    @GetMapping("/{id}/metrics")
    Map<String, Object> metrics(@PathVariable String id) {
        var app = applications.get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("latest", metrics.latest(id));
        m.put("history", metrics.history(id));
        var svc = services.primary(id);
        m.put("cpuLimit", svc.cpuLimit());
        m.put("memoryLimitMb", svc.memoryLimitMb());
        m.put("restartCount", svc.restartCount());
        ContainerInfo info = null;
        if (app.currentDeploymentId() != null) {
            Deployment d = deployments.get(app.currentDeploymentId());
            if (d.containerName() != null && !d.isStatic()) info = runtime.inspect(d.containerName()).orElse(null);
        }
        m.put("container", info);
        return m;
    }

    @GetMapping(path = "/{id}/events", produces = "text/event-stream")
    SseEmitter events(@PathVariable String id) {
        applications.get(id);
        return events.openStream(id);
    }

    // ------------------------------------------------------------------ environment

    @GetMapping("/{id}/environment")
    List<EnvironmentService.View> env(@PathVariable String id) {
        applications.get(id);
        return environment.list(id);
    }

    @PutMapping("/{id}/environment")
    List<EnvironmentService.View> setEnv(@PathVariable String id, @RequestBody EnvRequest req) {
        applications.get(id);
        environment.set(id, req.name(), req.value(), req.secret());
        return environment.list(id);
    }

    @DeleteMapping("/{id}/environment/{name}")
    List<EnvironmentService.View> deleteEnv(@PathVariable String id, @PathVariable String name) {
        environment.delete(id, name);
        return environment.list(id);
    }

    // ------------------------------------------------------------------ domains

    @GetMapping("/{id}/domains")
    List<Domain> domains(@PathVariable String id) {
        return domains.list(id);
    }

    @PostMapping("/{id}/domains")
    ResponseEntity<Domain> addDomain(@PathVariable String id, @RequestBody DomainRequest req) {
        applications.get(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(domains.add(id, req.hostname(),
                props.https() && (req.tlsEnabled() == null || req.tlsEnabled()), Boolean.TRUE.equals(req.primary())));
    }

    @PutMapping("/{id}/domains/{domainId}")
    Domain updateDomain(@PathVariable String id, @PathVariable String domainId, @RequestBody DomainRequest req) {
        return domains.update(id, domainId, req.tlsEnabled(), req.primary());
    }

    @DeleteMapping("/{id}/domains/{domainId}")
    ResponseEntity<Void> deleteDomain(@PathVariable String id, @PathVariable String domainId) {
        domains.remove(id, domainId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ webhook

    @GetMapping("/{id}/webhook")
    WebhookService.WebhookView webhook(@PathVariable String id) {
        return webhooks.view(id);
    }

    @PutMapping("/{id}/webhook")
    WebhookService.WebhookView updateWebhook(@PathVariable String id, @RequestBody WebhookSettings req) {
        if (req.deployOnPush() != null) webhooks.setDeployOnPush(id, req.deployOnPush());
        if (req.automaticDeployment() != null) {
            service.update(id, new ApplicationService.UpdateRequest(null, null, null, null, null, null, req.automaticDeployment(),
                    null, null, null));
        }
        return webhooks.view(id);
    }

    @GetMapping("/{id}/webhook/events")
    List<WebhookEvent> webhookEvents(@PathVariable String id, @RequestParam(defaultValue = "50") int limit) {
        applications.get(id);
        return webhooks.history(id, Math.min(limit, 500));
    }

    /** Reveals the secret once, for pasting into GitHub. Audited. */
    @PostMapping("/{id}/webhook/secret/reveal")
    Map<String, String> revealSecret(@PathVariable String id) {
        audit.record("WEBHOOK_SECRET_REVEALED", "APPLICATION", id, Map.of());
        return Map.of("secret", webhooks.revealSecret(id));
    }

    @PostMapping("/{id}/webhook/secret")
    Map<String, String> regenerateSecret(@PathVariable String id) {
        return Map.of("secret", webhooks.regenerateSecret(id));
    }

    @PostMapping("/{id}/webhook/test")
    WebhookService.Result testWebhook(@PathVariable String id) {
        return webhooks.test(id);
    }

    // ------------------------------------------------------------------ audit

    @GetMapping("/{id}/activity")
    List<AuditService.AuditEvent> activity(@PathVariable String id, @RequestParam(defaultValue = "50") int limit) {
        return audit.recent(Math.min(limit, 500), id);
    }

    private Deployment find(String applicationId, String deploymentId) {
        Deployment d = deployments.findById(deploymentId).orElse(null);
        if (d == null) {
            // Also accept deployment numbers (#42) in URLs.
            try {
                d = deployments.findByNumber(applicationId, Integer.parseInt(deploymentId)).orElse(null);
            } catch (NumberFormatException ignored) {
                // not a number
            }
        }
        if (d == null || !d.applicationId().equals(applicationId)) throw new NotFoundException("Deployment not found");
        return d;
    }
}
