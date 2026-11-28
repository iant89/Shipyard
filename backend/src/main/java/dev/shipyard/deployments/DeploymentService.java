package dev.shipyard.deployments;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.applications.ApplicationStatus;
import dev.shipyard.applications.ServiceRepository;
import dev.shipyard.audit.AuditActions;
import dev.shipyard.audit.AuditService;
import dev.shipyard.caddy.CaddyManager;
import dev.shipyard.common.BadRequestException;
import dev.shipyard.common.ConflictException;
import dev.shipyard.common.Ids;
import dev.shipyard.common.NotFoundException;
import dev.shipyard.common.Time;
import dev.shipyard.events.EventBus;
import dev.shipyard.events.EventTypes;
import dev.shipyard.repositories.GitRepositoryStore;
import dev.shipyard.runtime.ContainerRuntime;
import dev.shipyard.webhooks.WebhookEventStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entry point for every deployment trigger. Manual, IDE, webhook, API, scheduled
 * and system deployments all become a {@link Deployment} plus a queue item
 * here, and are executed by the same {@link DeploymentPipeline}.
 */
@Service
public class DeploymentService {

    /** A request to deploy. {@code ref} is a branch name or commit SHA; null means the configured branch. */
    public record DeploymentRequest(String applicationId, DeploymentSource source, String ref, String commitSha, String branch,
                                    String commitMessage, String commitAuthor, String triggeredBy) {}

    private final DeploymentRepository deployments;
    private final DeploymentQueue queue;
    private final ApplicationRepository applications;
    private final ServiceRepository services;
    private final GitRepositoryStore repositories;
    private final ContainerRuntime runtime;
    private final CaddyManager caddy;
    private final EventBus events;
    private final AuditService audit;
    private final WebhookEventStore webhookEvents;
    private final DeploymentWorker worker;

    public DeploymentService(DeploymentRepository deployments, DeploymentQueue queue, ApplicationRepository applications,
                             ServiceRepository services, GitRepositoryStore repositories, ContainerRuntime runtime,
                             CaddyManager caddy, EventBus events, AuditService audit, WebhookEventStore webhookEvents,
                             DeploymentWorker worker) {
        this.deployments = deployments;
        this.queue = queue;
        this.applications = applications;
        this.services = services;
        this.repositories = repositories;
        this.runtime = runtime;
        this.caddy = caddy;
        this.events = events;
        this.audit = audit;
        this.webhookEvents = webhookEvents;
        this.worker = worker;
    }

    @Transactional
    public Deployment request(DeploymentRequest req) {
        Application app = applications.get(req.applicationId());
        var repo = repositories.forApplication(app.id());
        var service = services.primary(app.id());
        String branch = req.branch() != null ? req.branch() : repo.branch();
        String commit = req.commitSha();
        if (commit == null && req.ref() != null && !req.ref().isBlank()) {
            String ref = req.ref().strip();
            if (ref.matches("[0-9a-fA-F]{7,40}")) commit = ref;
            else branch = ref;
        }

        // Newest commit wins: queued (not yet started) deployments become stale.
        if (app.cancelStaleBuilds()) {
            for (QueueItem q : queue.queuedFor(app.id())) {
                if (QueueItem.DEPLOY.equals(q.kind()) && queue.cancelIfQueued(q.id())) {
                    deployments.finish(q.deploymentId(), DeploymentStatus.CANCELLED, "Superseded by a newer deployment");
                    webhookEvents.updateByDeployment(q.deploymentId(), "FAILED", "Superseded by a newer deployment");
                    publishStatus(app.id(), q.deploymentId(), DeploymentStatus.CANCELLED);
                }
            }
        }

        Deployment d = new Deployment(Ids.uuid(), app.id(), service.id(), deployments.nextNumber(app.id()), commit, req.commitMessage(),
                req.commitAuthor(), branch, req.source(), DeploymentStatus.CREATED, "PENDING", "PENDING", null, null, null, null,
                null, null, null, req.triggeredBy(), Time.now(), null, null, null);
        deployments.insert(d);
        queue.enqueue(app.id(), d.id(), QueueItem.DEPLOY, req.source().name(), commit, branch,
                req.source() == DeploymentSource.MANUAL || req.source() == DeploymentSource.IDE ? 10 : 0);
        deployments.updateStatus(d.id(), DeploymentStatus.QUEUED);
        audit.record(AuditActions.DEPLOYMENT_STARTED, "DEPLOYMENT", d.id(),
                Map.of("application", app.slug(), "number", d.number(), "source", req.source().name(), "ref", commit != null ? commit : branch));
        events.publish(EventTypes.DEPLOYMENT_REQUESTED, app.id(), d.id(), Map.of("number", d.number(), "source", req.source().name()));
        publishStatus(app.id(), d.id(), DeploymentStatus.QUEUED);
        worker.wake();
        return deployments.get(d.id());
    }

    /**
     * Re-activates a previous deployment's artifact (no rebuild). Goes through
     * the queue so it never races with a running deployment.
     */
    @Transactional
    public Deployment rollback(String applicationId, String targetDeploymentId) {
        Application app = applications.get(applicationId);
        Deployment target;
        if (targetDeploymentId == null) {
            target = previousHealthy(app).orElseThrow(() -> new BadRequestException("No previous deployment to roll back to"));
        } else {
            target = deployments.get(targetDeploymentId);
            if (!target.applicationId().equals(applicationId)) throw new NotFoundException("Deployment not found");
        }
        if (target.id().equals(app.currentDeploymentId()) && app.status() == ApplicationStatus.RUNNING) {
            throw new ConflictException("Deployment #" + target.number() + " is already active");
        }
        if (target.imageTag() == null && target.artifactPath() == null) {
            throw new BadRequestException("Deployment #" + target.number() + " has no artifact to roll back to");
        }
        queue.enqueue(app.id(), target.id(), QueueItem.ROLLBACK, DeploymentSource.MANUAL.name(), target.commitSha(), target.branch(), 20);
        events.publish(EventTypes.DEPLOYMENT_REQUESTED, app.id(), target.id(), Map.of("number", target.number(), "rollback", true));
        worker.wake();
        return target;
    }

    Optional<Deployment> previousHealthy(Application app) {
        return deployments.findByApplication(app.id(), 100).stream()
                .filter(d -> !d.id().equals(app.currentDeploymentId()))
                .filter(d -> d.status() == DeploymentStatus.STOPPED || d.status() == DeploymentStatus.ROLLED_BACK
                        || d.status() == DeploymentStatus.RUNNING)
                .filter(d -> d.imageTag() != null || d.artifactPath() != null)
                .findFirst();
    }

    /** Cancels a queued deployment, or signals a running one to stop at the next step. */
    @Transactional
    public void cancel(String applicationId, String deploymentId) {
        Deployment d = deployments.get(deploymentId);
        if (!d.applicationId().equals(applicationId)) throw new NotFoundException("Deployment not found");
        Optional<QueueItem> q = queue.forDeployment(deploymentId);
        if (q.isPresent() && queue.cancelIfQueued(q.get().id())) {
            deployments.finish(deploymentId, DeploymentStatus.CANCELLED, "Cancelled by user");
            webhookEvents.updateByDeployment(deploymentId, "FAILED", "Cancelled by user");
            publishStatus(applicationId, deploymentId, DeploymentStatus.CANCELLED);
            return;
        }
        if (!worker.cancelRunning(applicationId, deploymentId)) {
            throw new ConflictException("Deployment #" + d.number() + " is not queued or running");
        }
    }

    public void restart(String applicationId) {
        Application app = applications.get(applicationId);
        Deployment current = current(app);
        if (current.isStatic()) {
            caddy.reload();
        } else {
            if (runtime.inspect(current.containerName()).isEmpty()) {
                throw new ConflictException("Container for deployment #" + current.number() + " no longer exists; redeploy instead");
            }
            runtime.restart(current.containerName());
        }
        services.setRestartCount(current.serviceId(), 0);
        applications.updateStatus(app.id(), ApplicationStatus.RUNNING);
        deployments.updateStatus(current.id(), DeploymentStatus.RUNNING);
        deployments.setRuntimeStatus(current.id(), "RUNNING");
        events.publish(EventTypes.APPLICATION_STARTED, app.id(), current.id(), Map.of("action", "restart"));
        audit.record(AuditActions.APPLICATION_UPDATED, "APPLICATION", app.id(), Map.of("action", "restart"));
    }

    public void stop(String applicationId) {
        Application app = applications.get(applicationId);
        Deployment current = current(app);
        if (!current.isStatic()) runtime.stop(current.containerName(), 20);
        applications.updateStatus(app.id(), ApplicationStatus.STOPPED);
        deployments.updateStatus(current.id(), DeploymentStatus.STOPPED);
        deployments.setRuntimeStatus(current.id(), "STOPPED");
        caddy.reloadAsync();
        events.publish(EventTypes.APPLICATION_STOPPED, app.id(), current.id(), Map.of());
        audit.record(AuditActions.APPLICATION_UPDATED, "APPLICATION", app.id(), Map.of("action", "stop"));
    }

    public void start(String applicationId) {
        Application app = applications.get(applicationId);
        Deployment current = current(app);
        if (!current.isStatic()) {
            if (runtime.inspect(current.containerName()).isEmpty()) {
                throw new ConflictException("Container for deployment #" + current.number() + " no longer exists; redeploy instead");
            }
            runtime.start(current.containerName());
        }
        applications.updateStatus(app.id(), ApplicationStatus.RUNNING);
        deployments.updateStatus(current.id(), DeploymentStatus.RUNNING);
        deployments.setRuntimeStatus(current.id(), "RUNNING");
        caddy.reloadAsync();
        events.publish(EventTypes.APPLICATION_STARTED, app.id(), current.id(), Map.of("action", "start"));
        audit.record(AuditActions.APPLICATION_UPDATED, "APPLICATION", app.id(), Map.of("action", "start"));
    }

    public List<Deployment> list(String applicationId, int limit) {
        return deployments.findByApplication(applicationId, limit);
    }

    private Deployment current(Application app) {
        if (app.currentDeploymentId() == null) throw new ConflictException("Application has no active deployment");
        return deployments.get(app.currentDeploymentId());
    }

    private void publishStatus(String applicationId, String deploymentId, DeploymentStatus status) {
        events.publish(EventTypes.DEPLOYMENT_STATUS, applicationId, deploymentId, Map.of("status", status.name()));
    }
}
