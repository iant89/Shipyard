package dev.shipyard.applications;

import dev.shipyard.audit.AuditActions;
import dev.shipyard.audit.AuditService;
import dev.shipyard.caddy.CaddyManager;
import dev.shipyard.common.BadRequestException;
import dev.shipyard.common.Ids;
import dev.shipyard.common.Slugs;
import dev.shipyard.common.Time;
import dev.shipyard.deployments.Deployment;
import dev.shipyard.deployments.DeploymentQueue;
import dev.shipyard.deployments.DeploymentRepository;
import dev.shipyard.deployments.DeploymentService;
import dev.shipyard.deployments.DeploymentSource;
import dev.shipyard.deployments.DeploymentStatus;
import dev.shipyard.deployments.QueueItem;
import dev.shipyard.domains.DomainService;
import dev.shipyard.environment.EnvironmentService;
import dev.shipyard.events.EventBus;
import dev.shipyard.events.EventTypes;
import dev.shipyard.metrics.MetricsService;
import dev.shipyard.repositories.GitRepository;
import dev.shipyard.repositories.GitRepositoryStore;
import dev.shipyard.repositories.RepositoryManager;
import dev.shipyard.runtime.ContainerRuntime;
import dev.shipyard.security.SecretCipher;
import dev.shipyard.storage.StorageLayout;
import dev.shipyard.webhooks.WebhookService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {

    /** Build/runtime overrides. Null = use detection; empty string = explicitly none. */
    public record ServiceSettings(String projectType, String buildStrategy, String rootDirectory, String installCommand,
                                  String buildCommand, String testCommand, String startCommand, String outputDirectory,
                                  String runtimeImage, Integer internalPort, String healthCheckType, String healthCheckPath,
                                  Integer healthCheckTimeoutSeconds, Double cpuLimit, Integer memoryLimitMb, String restartPolicy) {}

    public record EnvInput(String name, String value, boolean secret) {}

    public record CreateRequest(String name, String description, String repository, String branch, String accessToken,
                                boolean autoDeploy, List<String> domains, ServiceSettings service, List<EnvInput> environment,
                                boolean deployNow, Boolean autoUpdate) {}

    public record UpdateRequest(String name, String description, String repository, String branch, String accessToken,
                                Boolean clearAccessToken, Boolean autoDeploy, Boolean autoRollback, Boolean cancelStaleBuilds,
                                ServiceSettings service, Boolean autoUpdate) {}

    private final ApplicationRepository applications;
    private final ServiceRepository services;
    private final GitRepositoryStore repositories;
    private final DeploymentRepository deployments;
    private final DeploymentQueue queue;
    private final DeploymentService deploymentService;
    private final WebhookService webhooks;
    private final DomainService domains;
    private final EnvironmentService environment;
    private final ContainerRuntime runtime;
    private final CaddyManager caddy;
    private final SecretCipher cipher;
    private final StorageLayout layout;
    private final RepositoryManager repoManager;
    private final MetricsService metrics;
    private final AuditService audit;
    private final EventBus events;
    private final dev.shipyard.config.PlatformProperties props;

    public ApplicationService(dev.shipyard.config.PlatformProperties props, ApplicationRepository applications, ServiceRepository services, GitRepositoryStore repositories,
                              DeploymentRepository deployments, DeploymentQueue queue, DeploymentService deploymentService,
                              WebhookService webhooks, DomainService domains, EnvironmentService environment, ContainerRuntime runtime,
                              CaddyManager caddy, SecretCipher cipher, StorageLayout layout, RepositoryManager repoManager,
                              MetricsService metrics, AuditService audit, EventBus events) {
        this.props = props;
        this.applications = applications;
        this.services = services;
        this.repositories = repositories;
        this.deployments = deployments;
        this.queue = queue;
        this.deploymentService = deploymentService;
        this.webhooks = webhooks;
        this.domains = domains;
        this.environment = environment;
        this.runtime = runtime;
        this.caddy = caddy;
        this.cipher = cipher;
        this.layout = layout;
        this.repoManager = repoManager;
        this.metrics = metrics;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    public Application create(CreateRequest req) {
        if (req.name() == null || req.name().isBlank()) throw new BadRequestException("Name is required");
        String url = checkUrl(RepositoryManager.normalizeUrl(req.repository()));
        String branch = req.branch() == null || req.branch().isBlank() ? "main" : req.branch().strip();
        String slug = uniqueSlug(req.name());
        String now = Time.now();
        Application app = new Application(Ids.uuid(), slug, req.name().strip(), blankToNull(req.description()), ApplicationStatus.CREATED,
                null, req.autoDeploy(), true, true, 1, Boolean.TRUE.equals(req.autoUpdate()), now, now);
        applications.insert(app);

        ServiceSettings s = req.service() == null ? emptySettings() : req.service();
        services.insert(new ServiceConfig(Ids.uuid(), app.id(), ServiceConfig.DEFAULT_SERVICE, s.projectType(), null, s.buildStrategy(),
                s.rootDirectory() == null || s.rootDirectory().isBlank() ? "." : s.rootDirectory(), s.installCommand(), s.buildCommand(),
                s.testCommand(), s.startCommand(), s.outputDirectory(), null, s.runtimeImage(), s.internalPort(),
                s.healthCheckType() == null ? "HTTP" : s.healthCheckType(), s.healthCheckPath() == null ? "/" : s.healthCheckPath(),
                s.healthCheckTimeoutSeconds() == null ? 60 : s.healthCheckTimeoutSeconds(), s.cpuLimit() == null ? 1.0 : s.cpuLimit(),
                s.memoryLimitMb() == null ? 512 : s.memoryLimitMb(), s.restartPolicy() == null ? "unless-stopped" : s.restartPolicy(),
                0, now, now));
        validateLimits(services.primary(app.id()));

        repositories.insert(new GitRepository(Ids.uuid(), app.id(), "GITHUB", url, branch,
                req.accessToken() == null || req.accessToken().isBlank() ? null : cipher.encrypt(req.accessToken().strip()), now, now));
        webhooks.create(app.id());
        if (req.domains() != null) for (String d : req.domains()) if (d != null && !d.isBlank()) domains.add(app.id(), d, props.https(), false);
        if (req.environment() != null) for (EnvInput e : req.environment()) environment.set(app.id(), e.name(), e.value(), e.secret());

        audit.record(AuditActions.USER_CREATED_APPLICATION, "APPLICATION", app.id(), Map.of("name", app.name(), "repository", url));
        events.publish(EventTypes.APPLICATION_UPDATED, app.id(), null, Map.of("status", app.status().name(), "created", true));
        if (req.deployNow()) {
            deploymentService.request(new DeploymentService.DeploymentRequest(app.id(), DeploymentSource.MANUAL, null, null, null,
                    null, null, "user"));
        }
        return applications.get(app.id());
    }

    @Transactional
    public Application update(String id, UpdateRequest req) {
        Application a = applications.get(id);
        Application updated = new Application(a.id(), a.slug(),
                req.name() != null && !req.name().isBlank() ? req.name().strip() : a.name(),
                req.description() != null ? blankToNull(req.description()) : a.description(), a.status(), a.currentDeploymentId(),
                req.autoDeploy() != null ? req.autoDeploy() : a.autoDeploy(),
                req.autoRollback() != null ? req.autoRollback() : a.autoRollback(),
                req.cancelStaleBuilds() != null ? req.cancelStaleBuilds() : a.cancelStaleBuilds(),
                a.buildConcurrency(), req.autoUpdate() != null ? req.autoUpdate() : a.autoUpdate(), a.createdAt(), a.updatedAt());
        applications.update(updated);

        GitRepository r = repositories.forApplication(id);
        String token = r.accessTokenEnc();
        if (Boolean.TRUE.equals(req.clearAccessToken())) token = null;
        if (req.accessToken() != null && !req.accessToken().isBlank()) token = cipher.encrypt(req.accessToken().strip());
        repositories.update(new GitRepository(r.id(), r.applicationId(), r.provider(),
                req.repository() != null && !req.repository().isBlank() ? checkUrl(RepositoryManager.normalizeUrl(req.repository())) : r.url(),
                req.branch() != null && !req.branch().isBlank() ? req.branch().strip() : r.branch(), token, r.createdAt(), r.updatedAt()));

        if (req.service() != null) {
            ServiceConfig c = services.primary(id);
            ServiceSettings s = req.service();
            ServiceConfig next = new ServiceConfig(c.id(), c.applicationId(), c.name(),
                    emptyToNull(s.projectType()), c.framework(), emptyToNull(s.buildStrategy()),
                    s.rootDirectory() == null || s.rootDirectory().isBlank() ? "." : s.rootDirectory(),
                    s.installCommand(), s.buildCommand(), s.testCommand(), s.startCommand(), s.outputDirectory(), c.packageManager(),
                    emptyToNull(s.runtimeImage()), s.internalPort(),
                    s.healthCheckType() == null ? c.healthCheckType() : s.healthCheckType(),
                    s.healthCheckPath() == null ? c.healthCheckPath() : s.healthCheckPath(),
                    s.healthCheckTimeoutSeconds() == null ? c.healthCheckTimeoutSeconds() : s.healthCheckTimeoutSeconds(),
                    s.cpuLimit() == null ? c.cpuLimit() : s.cpuLimit(), s.memoryLimitMb() == null ? c.memoryLimitMb() : s.memoryLimitMb(),
                    s.restartPolicy() == null ? c.restartPolicy() : s.restartPolicy(), c.restartCount(), c.createdAt(), c.updatedAt());
            validateLimits(next);
            services.update(next);
        }
        audit.record(AuditActions.APPLICATION_UPDATED, "APPLICATION", id, Map.of());
        events.publish(EventTypes.APPLICATION_UPDATED, id, null, Map.of("status", a.status().name()));
        return applications.get(id);
    }

    /** Stops and removes everything the application owns. */
    public void delete(String id) {
        Application a = applications.get(id);
        for (QueueItem q : queue.queuedFor(id)) queue.cancelIfQueued(q.id());
        for (String c : runtime.listContainers("dev.shipyard.application", id)) runtime.remove(c);
        for (Deployment d : deployments.findByApplication(id, 10_000)) {
            if (d.containerName() != null) runtime.remove(d.containerName());
            if (d.imageTag() != null) runtime.removeImage(d.imageTag());
        }
        applications.delete(id);
        repoManager.delete(id);
        StorageLayout.deleteRecursively(layout.deploymentsDir(id));
        metrics.forget(id);
        caddy.reloadAsync();
        audit.record(AuditActions.APPLICATION_DELETED, "APPLICATION", id, Map.of("name", a.name()));
        events.publish(EventTypes.APPLICATION_UPDATED, id, null, Map.of("deleted", true));
    }

    public boolean hasActiveDeployment(String id) {
        return deployments.findByApplication(id, 20).stream()
                .anyMatch(d -> d.status() == DeploymentStatus.QUEUED || DeploymentStatus.IN_PROGRESS.contains(d.status()));
    }

    private String checkUrl(String url) {
        if (url.startsWith("file://") && !props.allowLocalRepositories()) {
            throw new BadRequestException("Local repositories are disabled (set SHIPYARD_ALLOW_LOCAL_REPOSITORIES=true)");
        }
        return url;
    }

    private String uniqueSlug(String name) {
        String base = Slugs.slugify(name);
        String slug = base;
        for (int i = 2; applications.slugExists(slug); i++) slug = base + "-" + i;
        return slug;
    }

    private static void validateLimits(ServiceConfig s) {
        if (s.cpuLimit() <= 0 || s.cpuLimit() > 64) throw new BadRequestException("CPU limit must be between 0 and 64 cores");
        if (s.memoryLimitMb() < 32 || s.memoryLimitMb() > 262_144) throw new BadRequestException("Memory limit must be between 32 MB and 256 GB");
        if (s.internalPort() != null && (s.internalPort() < 1 || s.internalPort() > 65535)) throw new BadRequestException("Invalid port");
        if (s.healthCheckTimeoutSeconds() < 5 || s.healthCheckTimeoutSeconds() > 900) throw new BadRequestException("Health check timeout must be 5-900 seconds");
        if (s.rootDirectory() != null && (s.rootDirectory().contains("..") || s.rootDirectory().startsWith("/"))) {
            throw new BadRequestException("Root directory must be a relative path inside the repository");
        }
    }

    private static ServiceSettings emptySettings() {
        return new ServiceSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
