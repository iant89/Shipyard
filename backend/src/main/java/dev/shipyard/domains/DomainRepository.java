package dev.shipyard.domains;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DomainRepository {
    private static final RowMapper<Domain> MAPPER = (rs, i) -> new Domain(rs.getString("id"), rs.getString("application_id"),
            rs.getString("hostname"), rs.getInt("tls_enabled") == 1, rs.getInt("is_primary") == 1, rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public DomainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Domain> findAll() {
        return jdbc.query("SELECT * FROM domains ORDER BY hostname", MAPPER);
    }

    public List<Domain> findByApplication(String applicationId) {
        return jdbc.query("SELECT * FROM domains WHERE application_id = ? ORDER BY is_primary DESC, hostname", MAPPER, applicationId);
    }

    public Optional<Domain> findById(String id) {
        return jdbc.query("SELECT * FROM domains WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public boolean hostnameExists(String hostname) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM domains WHERE hostname = ?", Long.class, hostname);
        return n != null && n > 0;
    }

    public void insert(Domain d) {
        jdbc.update("INSERT INTO domains(id, application_id, hostname, tls_enabled, is_primary, created_at) VALUES (?,?,?,?,?,?)",
                d.id(), d.applicationId(), d.hostname(), d.tlsEnabled() ? 1 : 0, d.primary() ? 1 : 0, d.createdAt());
    }

    public void update(Domain d) {
        jdbc.update("UPDATE domains SET tls_enabled = ?, is_primary = ? WHERE id = ?", d.tlsEnabled() ? 1 : 0, d.primary() ? 1 : 0, d.id());
    }

    public void clearPrimary(String applicationId) {
        jdbc.update("UPDATE domains SET is_primary = 0 WHERE application_id = ?", applicationId);
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM domains WHERE id = ?", id);
    }
}
