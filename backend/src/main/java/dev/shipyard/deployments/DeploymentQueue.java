package dev.shipyard.deployments;

import dev.shipyard.common.Ids;
import dev.shipyard.common.Time;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persistent deployment queue; survives platform restarts. */
@Repository
public class DeploymentQueue {
    private static final RowMapper<QueueItem> MAPPER = (rs, i) -> new QueueItem(rs.getString("id"), rs.getString("application_id"),
            rs.getString("deployment_id"), rs.getString("kind"), rs.getString("source"), rs.getString("commit_sha"),
            rs.getString("branch"), rs.getInt("priority"), rs.getString("status"), rs.getString("created_at"),
            rs.getString("started_at"), rs.getString("finished_at"));

    private final JdbcTemplate jdbc;

    public DeploymentQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public QueueItem enqueue(String applicationId, String deploymentId, String kind, String source, String commitSha, String branch, int priority) {
        QueueItem q = new QueueItem(Ids.uuid(), applicationId, deploymentId, kind, source, commitSha, branch, priority, "QUEUED",
                Time.now(), null, null);
        jdbc.update("""
                INSERT INTO deployment_queue(id, application_id, deployment_id, kind, source, commit_sha, branch, priority, status, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)""",
                q.id(), q.applicationId(), q.deploymentId(), q.kind(), q.source(), q.commitSha(), q.branch(), q.priority(), q.status(), q.createdAt());
        return q;
    }

    /** Queued items whose application has nothing running, oldest/highest priority first. */
    public List<QueueItem> runnable() {
        return jdbc.query("""
                SELECT * FROM deployment_queue q WHERE q.status = 'QUEUED'
                  AND NOT EXISTS (SELECT 1 FROM deployment_queue r WHERE r.application_id = q.application_id AND r.status = 'RUNNING')
                ORDER BY q.priority DESC, q.created_at""", MAPPER);
    }

    public List<QueueItem> queuedFor(String applicationId) {
        return jdbc.query("SELECT * FROM deployment_queue WHERE application_id = ? AND status = 'QUEUED' ORDER BY created_at", MAPPER, applicationId);
    }

    public List<QueueItem> byStatus(String status) {
        return jdbc.query("SELECT * FROM deployment_queue WHERE status = ? ORDER BY created_at", MAPPER, status);
    }

    public Optional<QueueItem> forDeployment(String deploymentId) {
        return jdbc.query("SELECT * FROM deployment_queue WHERE deployment_id = ? ORDER BY created_at DESC", MAPPER, deploymentId).stream().findFirst();
    }

    /** Claims an item atomically. Returns false if another worker got it first. */
    public boolean claim(String id) {
        return jdbc.update("UPDATE deployment_queue SET status = 'RUNNING', started_at = ? WHERE id = ? AND status = 'QUEUED'", Time.now(), id) == 1;
    }

    public void finish(String id, String status) {
        jdbc.update("UPDATE deployment_queue SET status = ?, finished_at = ? WHERE id = ?", status, Time.now(), id);
    }

    public boolean cancelIfQueued(String id) {
        return jdbc.update("UPDATE deployment_queue SET status = 'CANCELLED', finished_at = ? WHERE id = ? AND status = 'QUEUED'", Time.now(), id) == 1;
    }
}
