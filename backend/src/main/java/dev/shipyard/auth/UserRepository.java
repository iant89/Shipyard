package dev.shipyard.auth;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private static final RowMapper<User> MAPPER = (rs, i) -> new User(rs.getString("id"), rs.getString("username"),
            rs.getString("password_hash"), rs.getString("role"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByUsername(String username) {
        return jdbc.query("SELECT * FROM users WHERE username = ?", MAPPER, username).stream().findFirst();
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return n == null ? 0 : n;
    }

    public void insert(User u) {
        jdbc.update("INSERT INTO users(id, username, password_hash, role, created_at) VALUES (?,?,?,?,?)",
                u.id(), u.username(), u.passwordHash(), u.role(), u.createdAt());
    }

    public void updatePassword(String id, String hash) {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", hash, id);
    }
}
