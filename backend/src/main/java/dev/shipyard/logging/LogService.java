package dev.shipyard.logging;

import dev.shipyard.common.Time;
import dev.shipyard.events.EventBus;
import dev.shipyard.events.EventTypes;
import dev.shipyard.security.SecretMasker;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

/**
 * Stores build and deployment logs in SQLite and streams them live. Every
 * line passes through the application's {@link SecretMasker} first.
 */
@Service
public class LogService {
    private static final int MAX_LINE = 8000;

    private final JdbcTemplate jdbc;
    private final EventBus events;

    public LogService(JdbcTemplate jdbc, EventBus events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** A logger bound to one deployment. */
    public DeploymentLogger forDeployment(String applicationId, String deploymentId, SecretMasker masker) {
        return new DeploymentLogger(this, applicationId, deploymentId, masker);
    }

    void build(String applicationId, String deploymentId, String line, SecretMasker masker) {
        String l = truncate(masker.mask(line));
        long id = insert("INSERT INTO build_logs(deployment_id, stream, line, created_at) VALUES (?,?,?,?)", deploymentId, "stdout", l);
        events.publish(EventTypes.BUILD_LOG, applicationId, deploymentId, Map.of("id", id, "line", l));
    }

    void deployment(String applicationId, String deploymentId, String level, String line, SecretMasker masker) {
        String l = truncate(masker.mask(line));
        long id = insert("INSERT INTO deployment_logs(deployment_id, level, line, created_at) VALUES (?,?,?,?)", deploymentId, level, l);
        events.publish(EventTypes.DEPLOYMENT_LOG, applicationId, deploymentId, Map.of("id", id, "level", level, "line", l));
    }

    public List<LogLine> buildLogs(String deploymentId, long afterId, int limit) {
        return jdbc.query("SELECT * FROM build_logs WHERE deployment_id = ? AND id > ? ORDER BY id LIMIT ?",
                (rs, i) -> new LogLine(rs.getLong("id"), deploymentId, "build", "INFO", rs.getString("line"), rs.getString("created_at")),
                deploymentId, afterId, limit);
    }

    public List<LogLine> deploymentLogs(String deploymentId, long afterId, int limit) {
        return jdbc.query("SELECT * FROM deployment_logs WHERE deployment_id = ? AND id > ? ORDER BY id LIMIT ?",
                (rs, i) -> new LogLine(rs.getLong("id"), deploymentId, "deployment", rs.getString("level"), rs.getString("line"), rs.getString("created_at")),
                deploymentId, afterId, limit);
    }

    private long insert(String sql, String deploymentId, String second, String line) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(sql, new String[] {"id"});
            ps.setString(1, deploymentId);
            ps.setString(2, second);
            ps.setString(3, line);
            ps.setString(4, Time.now());
            return ps;
        }, keys);
        Number n = keys.getKey();
        return n == null ? 0 : n.longValue();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > MAX_LINE ? s.substring(0, MAX_LINE) + " …[truncated]" : s;
    }
}
