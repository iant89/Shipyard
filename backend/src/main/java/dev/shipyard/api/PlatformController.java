package dev.shipyard.api;

import dev.shipyard.api.ApplicationViews.DeploymentSummary;
import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.applications.ApplicationStatus;
import dev.shipyard.audit.AuditService;
import dev.shipyard.caddy.CaddyManager;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.deployments.DeploymentRepository;
import dev.shipyard.deployments.DeploymentStatus;
import dev.shipyard.detection.DetectionService;
import dev.shipyard.domains.PublicUrls;
import dev.shipyard.mdns.MdnsHostnames;
import dev.shipyard.events.EventBus;
import dev.shipyard.metrics.MetricSample;
import dev.shipyard.metrics.MetricsService;
import dev.shipyard.runtime.ContainerRuntime;
import dev.shipyard.webhooks.WebhookEvent;
import dev.shipyard.webhooks.WebhookEventStore;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class PlatformController {
    public record DetectRequest(String repository, String branch, String accessToken, String rootDirectory) {}
    public record BranchesRequest(String repository, String accessToken) {}

    private final ApplicationRepository applications;
    private final DeploymentRepository deployments;
    private final WebhookEventStore webhookEvents;
    private final MetricsService metrics;
    private final ContainerRuntime runtime;
    private final CaddyManager caddy;
    private final AuditService audit;
    private final EventBus events;
    private final DetectionService detection;
    private final PlatformProperties props;
    private final PublicUrls publicUrls;
    private final MdnsHostnames mdns;

    public PlatformController(ApplicationRepository applications, DeploymentRepository deployments, WebhookEventStore webhookEvents,
                              MetricsService metrics, ContainerRuntime runtime, CaddyManager caddy, AuditService audit, EventBus events,
                              DetectionService detection, PlatformProperties props, PublicUrls publicUrls, MdnsHostnames mdns) {
        this.mdns = mdns;
        this.publicUrls = publicUrls;
        this.applications = applications;
        this.deployments = deployments;
        this.webhookEvents = webhookEvents;
        this.metrics = metrics;
        this.runtime = runtime;
        this.caddy = caddy;
        this.audit = audit;
        this.events = events;
        this.detection = detection;
        this.props = props;
    }

    @GetMapping("/dashboard")
    Map<String, Object> dashboard() {
        List<Application> apps = applications.findAll();
        Map<ApplicationStatus, Integer> counts = new EnumMap<>(ApplicationStatus.class);
        for (Application a : apps) counts.merge(a.status(), 1, Integer::sum);
        int deploying = count(counts, ApplicationStatus.DEPLOYING) + count(counts, ApplicationStatus.BUILDING)
                + count(counts, ApplicationStatus.STARTING) + count(counts, ApplicationStatus.ROLLING_BACK);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applications", apps.size());
        m.put("running", count(counts, ApplicationStatus.RUNNING));
        m.put("deploying", deploying);
        m.put("failed", count(counts, ApplicationStatus.FAILED) + count(counts, ApplicationStatus.CRASHED));
        m.put("stopped", count(counts, ApplicationStatus.STOPPED));
        m.put("activeDeployments", deployments.findByStatuses(DeploymentStatus.IN_PROGRESS).size()
                + deployments.findByStatuses(DeploymentStatus.PENDING).size());
        m.put("host", metrics.latestHost());
        m.put("hostHistory", metrics.hostHistory());
        m.put("recentDeployments", deployments.recent(10).stream().map(ApplicationViews::deployment).toList());
        m.put("recentActivity", audit.recent(15, null));
        return m;
    }

    @GetMapping("/platform/info")
    Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Shipyard");
        m.put("version", getClass().getPackage().getImplementationVersion() == null ? "dev" : getClass().getPackage().getImplementationVersion());
        String dockerVersion = runtime.version();
        m.put("dockerAvailable", dockerVersion != null);
        m.put("dockerVersion", dockerVersion);
        m.put("dockerNetwork", props.dockerNetwork());
        // Applications behind Caddy can trust this range as their proxy (e.g. X-Forwarded-For).
        m.put("dockerSubnet", dockerVersion == null ? null : runtime.networkSubnet(props.dockerNetwork()));
        m.put("publicUrl", props.publicUrlNoSlash());
        m.put("platformDomain", props.platformDomain());
        m.put("https", props.https());
        m.put("httpPort", publicUrls.httpPort());
        m.put("mdns", mdns.status());
        m.put("baseDomain", props.baseDomain());
        m.put("dataDir", props.dataDir());
        m.put("caddyAdminConfigured", props.caddyAdminUrl() != null && !props.caddyAdminUrl().isBlank());
        m.put("caddyStatus", caddy.lastStatus());
        m.put("workerThreads", props.workerThreads());
        m.put("retainDeployments", props.retainDeployments());
        return m;
    }

    @GetMapping("/platform/caddy")
    Map<String, Object> caddyConfig() {
        return Map.of("config", caddy.render(), "status", caddy.lastStatus());
    }

    @PostMapping("/platform/caddy/reload")
    CaddyManager.ReloadResult reloadCaddy() {
        return caddy.reload();
    }

    @GetMapping("/metrics/host")
    Map<String, Object> hostMetrics() {
        MetricSample latest = metrics.latestHost();
        return Map.of("latest", latest, "history", metrics.hostHistory());
    }

    @GetMapping("/deployments")
    List<DeploymentSummary> recentDeployments(@RequestParam(defaultValue = "50") int limit) {
        return deployments.recent(Math.min(limit, 500)).stream().map(ApplicationViews::deployment).toList();
    }

    @GetMapping("/activity")
    List<AuditService.AuditEvent> activity(@RequestParam(defaultValue = "100") int limit) {
        return audit.recent(Math.min(limit, 1000), null);
    }

    @GetMapping("/webhook-events")
    List<WebhookEvent> recentWebhookEvents(@RequestParam(defaultValue = "50") int limit) {
        return webhookEvents.recent(Math.min(limit, 500));
    }

    @GetMapping(path = "/events", produces = "text/event-stream")
    SseEmitter stream() {
        return events.openStream(null);
    }

    @PostMapping("/detect")
    DetectionService.Preview detect(@RequestBody DetectRequest req) {
        return detection.preview(req.repository(), req.branch(), req.accessToken(), req.rootDirectory());
    }

    @PostMapping("/repositories/branches")
    List<String> branches(@RequestBody BranchesRequest req) {
        return detection.branches(req.repository(), req.accessToken());
    }

    private static int count(Map<ApplicationStatus, Integer> m, ApplicationStatus s) {
        return m.getOrDefault(s, 0);
    }
}
