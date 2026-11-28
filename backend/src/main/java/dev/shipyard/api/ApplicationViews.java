package dev.shipyard.api;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ServiceConfig;
import dev.shipyard.applications.ServiceRepository;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.deployments.Deployment;
import dev.shipyard.deployments.DeploymentRepository;
import dev.shipyard.domains.Domain;
import dev.shipyard.domains.DomainRepository;
import dev.shipyard.metrics.MetricSample;
import dev.shipyard.metrics.MetricsService;
import dev.shipyard.repositories.GitRepository;
import dev.shipyard.repositories.GitRepositoryStore;
import dev.shipyard.repositories.RepositoryManager;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Assembles API views. Secrets (tokens, webhook secrets, env values) never appear here. */
@Component
public class ApplicationViews {

    public record RepositoryView(String provider, String url, String fullName, String branch, boolean privateRepository) {}

    public record ApplicationSummary(String id, String slug, String name, String description, String status,
                                     RepositoryView repository, DeploymentSummary currentDeployment, DeploymentSummary latestDeployment,
                                     String primaryUrl, List<String> urls, MetricSample metrics, String projectType, String framework,
                                     boolean autoDeploy, String updatedAt) {}

    public record ApplicationDetail(ApplicationSummary summary, ServiceConfig service, List<Domain> domains, boolean autoRollback,
                                    boolean cancelStaleBuilds, int buildConcurrency, String createdAt) {}

    public record DeploymentSummary(String id, int number, String commitSha, String commitMessage, String commitAuthor, String branch,
                                    String source, String status, String buildStatus, String runtimeStatus, String projectType,
                                    String framework, String imageTag, String containerName, String createdAt, String startedAt,
                                    String finishedAt, String error, String applicationId) {}

    private final GitRepositoryStore repositories;
    private final DeploymentRepository deployments;
    private final DomainRepository domains;
    private final ServiceRepository services;
    private final MetricsService metrics;
    private final PlatformProperties props;

    public ApplicationViews(GitRepositoryStore repositories, DeploymentRepository deployments, DomainRepository domains,
                            ServiceRepository services, MetricsService metrics, PlatformProperties props) {
        this.repositories = repositories;
        this.deployments = deployments;
        this.domains = domains;
        this.services = services;
        this.metrics = metrics;
        this.props = props;
    }

    public ApplicationSummary summary(Application a) {
        GitRepository r = repositories.findByApplication(a.id()).orElse(null);
        RepositoryView rv = r == null ? null : new RepositoryView(r.provider(), r.url(), RepositoryManager.fullName(r.url()), r.branch(), r.isPrivate());
        Deployment current = a.currentDeploymentId() == null ? null : deployments.findById(a.currentDeploymentId()).orElse(null);
        Deployment latest = deployments.findByApplication(a.id(), 1).stream().findFirst().orElse(null);
        List<String> urls = new ArrayList<>();
        String primary = null;
        for (Domain d : domains.findByApplication(a.id())) {
            String u = (props.https() && d.tlsEnabled() ? "https://" : "http://") + d.hostname();
            urls.add(u);
            if (d.primary()) primary = u;
        }
        if (props.baseDomain() != null && !props.baseDomain().isBlank()) urls.add((props.https() ? "https://" : "http://") + a.slug() + "." + props.baseDomain().strip());
        if (primary == null && !urls.isEmpty()) primary = urls.get(0);
        Deployment typed = current != null ? current : latest;
        return new ApplicationSummary(a.id(), a.slug(), a.name(), a.description(), a.status().name(), rv, deployment(current),
                deployment(latest), primary, urls, metrics.latest(a.id()), typed == null ? null : typed.projectType(),
                typed == null ? null : typed.framework(), a.autoDeploy(), a.updatedAt());
    }

    public ApplicationDetail detail(Application a) {
        return new ApplicationDetail(summary(a), services.primary(a.id()), domains.findByApplication(a.id()), a.autoRollback(),
                a.cancelStaleBuilds(), a.buildConcurrency(), a.createdAt());
    }

    public static DeploymentSummary deployment(Deployment d) {
        if (d == null) return null;
        return new DeploymentSummary(d.id(), d.number(), d.commitSha(), d.commitMessage(), d.commitAuthor(), d.branch(), d.source().name(),
                d.status().name(), d.buildStatus(), d.runtimeStatus(), d.projectType(), d.framework(), d.imageTag(), d.containerName(),
                d.createdAt(), d.startedAt(), d.finishedAt(), d.error(), d.applicationId());
    }
}
