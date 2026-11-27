package dev.shipyard.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shipyard.common.Ids;
import dev.shipyard.common.Time;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    public record AuditEvent(String id, String userId, String action, String resourceType, String resourceId, String timestamp, String metadata) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(String action, String resourceType, String resourceId, Map<String, ?> metadata) {
        String json;
        try {
            json = metadata == null || metadata.isEmpty() ? null : mapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            json = null;
        }
        jdbc.update("INSERT INTO audit_events(id, user_id, action, resource_type, resource_id, timestamp, metadata) VALUES (?,?,?,?,?,?,?)",
                Ids.uuid(), currentUser(), action, resourceType, resourceId, Time.now(), json);
    }

    public List<AuditEvent> recent(int limit, String resourceId) {
        String sql = "SELECT * FROM audit_events " + (resourceId != null ? "WHERE resource_id = ? " : "") + "ORDER BY timestamp DESC LIMIT ?";
        Object[] args = resourceId != null ? new Object[] {resourceId, limit} : new Object[] {limit};
        return jdbc.query(sql, (rs, i) -> new AuditEvent(rs.getString("id"), rs.getString("user_id"), rs.getString("action"),
                rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("timestamp"), rs.getString("metadata")), args);
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getName())) return "system";
        return a.getName();
    }
}
