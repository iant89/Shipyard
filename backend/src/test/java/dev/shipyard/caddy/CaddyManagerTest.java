package dev.shipyard.caddy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.shipyard.applications.Application;
import dev.shipyard.applications.ApplicationRepository;
import dev.shipyard.applications.ApplicationStatus;
import dev.shipyard.config.PlatformProperties;
import dev.shipyard.deployments.DeploymentRepository;
import dev.shipyard.domains.Domain;
import dev.shipyard.domains.DomainRepository;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CaddyManagerTest {
    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {"BUILDING", "STARTING", "DEPLOYING", "ROLLING_BACK"})
    void sendsApplicationTrafficToTheMaintenanceScreenWhileUpdating(ApplicationStatus status) {
        ApplicationRepository applications = mock(ApplicationRepository.class);
        DeploymentRepository deployments = mock(DeploymentRepository.class);
        DomainRepository domains = mock(DomainRepository.class);
        Application app = new Application("app-id", "demo", "Demo", null, status, "previous-deployment", true, false, true, 2,
                false, "created", "updated");
        when(applications.findAll()).thenReturn(List.of(app));
        when(domains.findByApplication(app.id())).thenReturn(
                List.of(new Domain("domain-id", app.id(), "demo.example.com", false, true, "created")));

        PlatformProperties properties = new PlatformProperties(null, null, null, null, null, null, null, null, false, null, null,
                0, 0, 0, false, null, null, null, null, false, null);
        CaddyManager manager = new CaddyManager(properties, null, applications, deployments, domains, null);

        String config = manager.render();

        assertThat(config).contains("http://demo.example.com {")
                .contains("This application is currently being updated, please try again in a few minutes.")
                .contains("header Content-Type \"text/html; charset=utf-8\"")
                .contains(" 503")
                .doesNotContain("Application has not been deployed yet");
        verify(deployments, never()).findById("previous-deployment");
    }
}
