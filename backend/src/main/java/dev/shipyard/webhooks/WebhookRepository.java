package dev.shipyard.webhooks;

import dev.shipyard.common.Time;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookRepository {
    private static final RowMapper<Webhook> MAPPER = (rs, i) -> new Webhook(rs.getString("id"), rs.getString("application_id"),
            rs.getString("provider"), rs.getString("endpoint_id"), rs.getString("secret_enc"), rs.getInt("deploy_on_push") == 1,
            rs.getString("created_at"), rs.getString("updated_at"));

    private final JdbcTemplate jdbc;

    public WebhookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Webhook> findByEndpoint(String provider, String endpointId) {
        return jdbc.query("SELECT * FROM webhooks WHERE provider = ? AND endpoint_id = ?", MAPPER, provider, endpointId).stream().findFirst();
    }

    public Optional<Webhook> findByApplication(String applicationId) {
        return jdbc.query("SELECT * FROM webhooks WHERE application_id = ?", MAPPER, applicationId).stream().findFirst();
    }

    public void insert(Webhook w) {
        jdbc.update("INSERT INTO webhooks(id, application_id, provider, endpoint_id, secret_enc, deploy_on_push, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                w.id(), w.applicationId(), w.provider(), w.endpointId(), w.secretEnc(), w.deployOnPush() ? 1 : 0, w.createdAt(), w.updatedAt());
    }

    public void updateSecret(String id, String secretEnc) {
        jdbc.update("UPDATE webhooks SET secret_enc = ?, updated_at = ? WHERE id = ?", secretEnc, Time.now(), id);
    }

    public void updateDeployOnPush(String id, boolean value) {
        jdbc.update("UPDATE webhooks SET deploy_on_push = ?, updated_at = ? WHERE id = ?", value ? 1 : 0, Time.now(), id);
    }
}
