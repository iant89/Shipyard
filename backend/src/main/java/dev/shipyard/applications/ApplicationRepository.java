package dev.shipyard.applications;

import dev.shipyard.common.NotFoundException;
import dev.shipyard.common.Time;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ApplicationRepository {
    static final RowMapper<Application> MAPPER = (rs, i) -> new Application(
            rs.getString("id"), rs.getString("slug"), rs.getString("name"), rs.getString("description"),
            ApplicationStatus.valueOf(rs.getString("status")), rs.getString("current_deployment_id"),
            rs.getInt("auto_deploy") == 1, rs.getInt("auto_rollback") == 1, rs.getInt("cancel_stale_builds") == 1,
            rs.getInt("build_concurrency"), rs.getString("created_at"), rs.getString("updated_at"));

    private final JdbcTemplate jdbc;

    public ApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Application> findAll() {
        return jdbc.query("SELECT * FROM applications ORDER BY name COLLATE NOCASE", MAPPER);
    }

    public Optional<Application> findById(String id) {
        return jdbc.query("SELECT * FROM applications WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Application get(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Application not found: " + id));
    }

    public boolean slugExists(String slug) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM applications WHERE slug = ?", Long.class, slug);
        return n != null && n > 0;
    }

    public void insert(Application a) {
        jdbc.update("""
                INSERT INTO applications(id, slug, name, description, status, current_deployment_id, auto_deploy, auto_rollback,
                    cancel_stale_builds, build_concurrency, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                a.id(), a.slug(), a.name(), a.description(), a.status().name(), a.currentDeploymentId(), b(a.autoDeploy()),
                b(a.autoRollback()), b(a.cancelStaleBuilds()), a.buildConcurrency(), a.createdAt(), a.updatedAt());
    }

    public void update(Application a) {
        jdbc.update("""
                UPDATE applications SET name = ?, description = ?, auto_deploy = ?, auto_rollback = ?, cancel_stale_builds = ?,
                    build_concurrency = ?, updated_at = ? WHERE id = ?""",
                a.name(), a.description(), b(a.autoDeploy()), b(a.autoRollback()), b(a.cancelStaleBuilds()),
                a.buildConcurrency(), Time.now(), a.id());
    }

    public void updateStatus(String id, ApplicationStatus status) {
        jdbc.update("UPDATE applications SET status = ?, updated_at = ? WHERE id = ?", status.name(), Time.now(), id);
    }

    public void setCurrentDeployment(String id, String deploymentId) {
        jdbc.update("UPDATE applications SET current_deployment_id = ?, updated_at = ? WHERE id = ?", deploymentId, Time.now(), id);
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM applications WHERE id = ?", id);
    }

    private static int b(boolean v) {
        return v ? 1 : 0;
    }
}
