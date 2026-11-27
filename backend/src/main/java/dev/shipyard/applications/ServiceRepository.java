package dev.shipyard.applications;

import dev.shipyard.common.NotFoundException;
import dev.shipyard.common.Time;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ServiceRepository {
    private static final RowMapper<ServiceConfig> MAPPER = (rs, i) -> new ServiceConfig(
            rs.getString("id"), rs.getString("application_id"), rs.getString("name"), rs.getString("project_type"),
            rs.getString("framework"), rs.getString("build_strategy"), rs.getString("root_directory"),
            rs.getString("install_command"), rs.getString("build_command"), rs.getString("test_command"),
            rs.getString("start_command"), rs.getString("output_directory"), rs.getString("package_manager"),
            rs.getString("runtime_image"), (Integer) rs.getObject("internal_port"), rs.getString("health_check_type"),
            rs.getString("health_check_path"), rs.getInt("health_check_timeout_seconds"), rs.getDouble("cpu_limit"),
            rs.getInt("memory_limit_mb"), rs.getString("restart_policy"), rs.getInt("restart_count"),
            rs.getString("created_at"), rs.getString("updated_at"));

    private final JdbcTemplate jdbc;

    public ServiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ServiceConfig> findByApplication(String applicationId) {
        return jdbc.query("SELECT * FROM application_services WHERE application_id = ? ORDER BY name", MAPPER, applicationId);
    }

    /** The MVP's single service. */
    public ServiceConfig primary(String applicationId) {
        return findByApplication(applicationId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Application has no service: " + applicationId));
    }

    public ServiceConfig get(String id) {
        return jdbc.query("SELECT * FROM application_services WHERE id = ?", MAPPER, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Service not found: " + id));
    }

    public void insert(ServiceConfig s) {
        jdbc.update("""
                INSERT INTO application_services(id, application_id, name, project_type, framework, build_strategy, root_directory,
                  install_command, build_command, test_command, start_command, output_directory, package_manager, runtime_image,
                  internal_port, health_check_type, health_check_path, health_check_timeout_seconds, cpu_limit, memory_limit_mb,
                  restart_policy, restart_count, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                s.id(), s.applicationId(), s.name(), s.projectType(), s.framework(), s.buildStrategy(), s.rootDirectory(),
                s.installCommand(), s.buildCommand(), s.testCommand(), s.startCommand(), s.outputDirectory(), s.packageManager(),
                s.runtimeImage(), s.internalPort(), s.healthCheckType(), s.healthCheckPath(), s.healthCheckTimeoutSeconds(),
                s.cpuLimit(), s.memoryLimitMb(), s.restartPolicy(), s.restartCount(), s.createdAt(), s.updatedAt());
    }

    public void update(ServiceConfig s) {
        jdbc.update("""
                UPDATE application_services SET project_type = ?, framework = ?, build_strategy = ?, root_directory = ?,
                  install_command = ?, build_command = ?, test_command = ?, start_command = ?, output_directory = ?,
                  package_manager = ?, runtime_image = ?, internal_port = ?, health_check_type = ?, health_check_path = ?,
                  health_check_timeout_seconds = ?, cpu_limit = ?, memory_limit_mb = ?, restart_policy = ?, updated_at = ?
                WHERE id = ?""",
                s.projectType(), s.framework(), s.buildStrategy(), s.rootDirectory(), s.installCommand(), s.buildCommand(),
                s.testCommand(), s.startCommand(), s.outputDirectory(), s.packageManager(), s.runtimeImage(), s.internalPort(),
                s.healthCheckType(), s.healthCheckPath(), s.healthCheckTimeoutSeconds(), s.cpuLimit(), s.memoryLimitMb(),
                s.restartPolicy(), Time.now(), s.id());
    }

    public void setRestartCount(String id, int count) {
        jdbc.update("UPDATE application_services SET restart_count = ? WHERE id = ?", count, id);
    }
}
