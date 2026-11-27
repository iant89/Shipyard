package dev.shipyard.repositories;

import dev.shipyard.common.NotFoundException;
import dev.shipyard.common.Time;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class GitRepositoryStore {
    private static final RowMapper<GitRepository> MAPPER = (rs, i) -> new GitRepository(rs.getString("id"),
            rs.getString("application_id"), rs.getString("provider"), rs.getString("url"), rs.getString("branch"),
            rs.getString("access_token_enc"), rs.getString("created_at"), rs.getString("updated_at"));

    private final JdbcTemplate jdbc;

    public GitRepositoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public GitRepository forApplication(String applicationId) {
        return jdbc.query("SELECT * FROM repositories WHERE application_id = ?", MAPPER, applicationId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Application has no repository: " + applicationId));
    }

    public List<GitRepository> findAll() {
        return jdbc.query("SELECT * FROM repositories", MAPPER);
    }

    public Optional<GitRepository> findByApplication(String applicationId) {
        return jdbc.query("SELECT * FROM repositories WHERE application_id = ?", MAPPER, applicationId).stream().findFirst();
    }

    public void insert(GitRepository r) {
        jdbc.update("INSERT INTO repositories(id, application_id, provider, url, branch, access_token_enc, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                r.id(), r.applicationId(), r.provider(), r.url(), r.branch(), r.accessTokenEnc(), r.createdAt(), r.updatedAt());
    }

    public void update(GitRepository r) {
        jdbc.update("UPDATE repositories SET provider = ?, url = ?, branch = ?, access_token_enc = ?, updated_at = ? WHERE id = ?",
                r.provider(), r.url(), r.branch(), r.accessTokenEnc(), Time.now(), r.id());
    }
}
