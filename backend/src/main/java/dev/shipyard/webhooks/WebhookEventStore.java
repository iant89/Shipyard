package dev.shipyard.webhooks;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookEventStore {
    private static final RowMapper<WebhookEvent> MAPPER = (rs, i) -> new WebhookEvent(rs.getString("id"), rs.getString("application_id"),
            rs.getString("provider"), rs.getString("event_type"), rs.getString("delivery_id"), rs.getString("repository"),
            rs.getString("branch"), rs.getString("commit_sha"), rs.getString("previous_sha"), rs.getString("author"),
            rs.getString("commit_message"), rs.getString("received_at"), rs.getString("status"), rs.getString("deployment_id"),
            rs.getString("error"), rs.getInt("test") == 1);

    private final JdbcTemplate jdbc;

    public WebhookEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the event; returns false when (provider, deliveryId) was already recorded. */
    public boolean insert(WebhookEvent e) {
        try {
            jdbc.update("""
                    INSERT INTO webhook_events(id, application_id, provider, event_type, delivery_id, repository, branch, commit_sha,
                      previous_sha, author, commit_message, received_at, status, deployment_id, error, test)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    e.id(), e.applicationId(), e.provider(), e.eventType(), e.deliveryId(), e.repository(), e.branch(), e.commitSha(),
                    e.previousSha(), e.author(), e.commitMessage(), e.receivedAt(), e.status(), e.deploymentId(), e.error(), e.test() ? 1 : 0);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("UNIQUE")) return false;
            throw ex;
        }
    }

    public boolean deliveryExists(String provider, String deliveryId) {
        if (deliveryId == null) return false;
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events WHERE provider = ? AND delivery_id = ?", Long.class, provider, deliveryId);
        return n != null && n > 0;
    }

    public void update(String id, String status, String deploymentId, String error) {
        jdbc.update("UPDATE webhook_events SET status = ?, deployment_id = COALESCE(?, deployment_id), error = ? WHERE id = ?",
                status, deploymentId, error, id);
    }

    public void updateByDeployment(String deploymentId, String status, String error) {
        jdbc.update("UPDATE webhook_events SET status = ?, error = COALESCE(?, error) WHERE deployment_id = ?", status, error, deploymentId);
    }

    public List<WebhookEvent> forApplication(String applicationId, int limit) {
        return jdbc.query("SELECT * FROM webhook_events WHERE application_id = ? ORDER BY received_at DESC LIMIT ?", MAPPER, applicationId, limit);
    }

    public List<WebhookEvent> recent(int limit) {
        return jdbc.query("SELECT * FROM webhook_events ORDER BY received_at DESC LIMIT ?", MAPPER, limit);
    }

    public Optional<WebhookEvent> lastForApplication(String applicationId) {
        return jdbc.query("SELECT * FROM webhook_events WHERE application_id = ? AND test = 0 ORDER BY received_at DESC LIMIT 1", MAPPER, applicationId)
                .stream().findFirst();
    }
}
