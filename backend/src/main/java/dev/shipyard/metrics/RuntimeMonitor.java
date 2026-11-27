package dev.shipyard.metrics;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.applications.ApplicationStatus;
import dev.shipyard.applications.ServiceRepository;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.deployments.Deployment;
import dev.shipyard.deployments.DeploymentRepository;
import dev.shipyard.events.EventBus;
import dev.shipyard.events.EventTypes;
import dev.shipyard.runtime.ContainerInfo;
import dev.shipyard.runtime.ContainerRuntime;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Watches running application containers. Docker restarts crashed
 * containers automatically; once the restart count passes the configured
 * threshold the application is marked CRASHED. It returns to RUNNING when
 * the container stays up again.
 */
@Component
public class RuntimeMonitor {
    private static final Logger log = LoggerFactory.getLogger(RuntimeMonitor.class);

    private final ApplicationRepository applications;
    private final DeploymentRepository deployments;
    private final ServiceRepository services;
    private final ContainerRuntime runtime;
    private final EventBus events;
    private final PlatformProperties props;
    private final Map<String, Integer> lastRestarts = new java.util.concurrent.ConcurrentHashMap<>();

    public RuntimeMonitor(ApplicationRepository applications, DeploymentRepository deployments, ServiceRepository services,
                          ContainerRuntime runtime, EventBus events, PlatformProperties props) {
        this.applications = applications;
        this.deployments = deployments;
        this.services = services;
        this.runtime = runtime;
        this.events = events;
        this.props = props;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    void check() {
        if (!runtime.available()) return;
        for (Application app : applications.findAll()) {
            if (app.status() != ApplicationStatus.RUNNING && app.status() != ApplicationStatus.CRASHED) continue;
            if (app.currentDeploymentId() == null) continue;
            Deployment d = deployments.findById(app.currentDeploymentId()).orElse(null);
            if (d == null || d.isStatic() || d.containerName() == null) continue;
            Optional<ContainerInfo> info = runtime.inspect(d.containerName());
            int restarts = info.map(ContainerInfo::restartCount).orElse(0);
            services.setRestartCount(d.serviceId(), restarts);
            boolean up = info.map(ContainerInfo::running).orElse(false);
            Integer previous = lastRestarts.put(d.id(), restarts);
            boolean restartedSinceLastCheck = previous != null && restarts > previous;
            // Crashing: the container is gone/exited, or it keeps restarting and has passed the threshold.
            boolean crashed = info.isEmpty() || (!up && "exited".equals(info.get().status()))
                    || (restartedSinceLastCheck && restarts >= props.crashRestartThreshold());
            if (crashed && app.status() != ApplicationStatus.CRASHED) {
                applications.updateStatus(app.id(), ApplicationStatus.CRASHED);
                deployments.setRuntimeStatus(d.id(), "CRASHED");
                log.warn("Application {} crashed (restart count {})", app.slug(), restarts);
                events.publish(EventTypes.APPLICATION_CRASHED, app.id(), d.id(), Map.of("restartCount", restarts,
                        "containerStatus", info.map(ContainerInfo::status).orElse("missing")));
                events.publish(EventTypes.APPLICATION_UPDATED, app.id(), null, Map.of("status", "CRASHED"));
            } else if (!crashed && up && app.status() == ApplicationStatus.CRASHED) {
                applications.updateStatus(app.id(), ApplicationStatus.RUNNING);
                deployments.setRuntimeStatus(d.id(), "RUNNING");
                events.publish(EventTypes.APPLICATION_STARTED, app.id(), d.id(), Map.of("recovered", true));
                events.publish(EventTypes.APPLICATION_UPDATED, app.id(), null, Map.of("status", "RUNNING"));
            }
        }
    }
}
