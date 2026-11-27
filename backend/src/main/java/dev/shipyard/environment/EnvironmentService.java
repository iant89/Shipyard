package dev.shipyard.environment;

import dev.shipyard.audit.AuditActions;
import dev.shipyard.audit.AuditService;
import dev.shipyard.common.BadRequestException;
import dev.shipyard.common.Ids;
import dev.shipyard.common.Time;
import dev.shipyard.security.SecretCipher;
import dev.shipyard.security.SecretMasker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application environment variables, encrypted at rest. Values marked
 * secret are never returned by the API and are masked in every log.
 * Non-secret values are also passed to builds as build arguments (needed
 * for e.g. VITE_* variables in static sites); secrets are runtime-only.
 */
@Service
public class EnvironmentService {
    public record View(String id, String name, String value, boolean secret, String updatedAt) {}

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final RowMapper<EnvironmentVariable> MAPPER = (rs, i) -> new EnvironmentVariable(rs.getString("id"),
            rs.getString("application_id"), rs.getString("name"), rs.getString("value_enc"), rs.getInt("secret") == 1,
            rs.getString("created_at"), rs.getString("updated_at"));

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final AuditService audit;

    public EnvironmentService(JdbcTemplate jdbc, SecretCipher cipher, AuditService audit) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.audit = audit;
    }

    public List<View> list(String applicationId) {
        return rows(applicationId).stream()
                .map(v -> new View(v.id(), v.name(), v.secret() ? SecretMasker.MASK : cipher.decrypt(v.valueEnc()), v.secret(), v.updatedAt()))
                .toList();
    }

    /**
     * Creates or updates a variable. A null value on an existing secret keeps
     * the stored value (so the UI can toggle flags without re-entering it).
     */
    @Transactional
    public void set(String applicationId, String name, String value, boolean secret) {
        if (name == null || !NAME.matcher(name).matches()) throw new BadRequestException("Invalid variable name: " + name);
        var existing = rows(applicationId).stream().filter(v -> v.name().equals(name)).findFirst();
        if (existing.isPresent()) {
            String enc = value == null ? existing.get().valueEnc() : cipher.encrypt(value);
            jdbc.update("UPDATE environment_variables SET value_enc = ?, secret = ?, updated_at = ? WHERE id = ?",
                    enc, secret ? 1 : 0, Time.now(), existing.get().id());
        } else {
            if (value == null) throw new BadRequestException("Value is required");
            String now = Time.now();
            jdbc.update("INSERT INTO environment_variables(id, application_id, name, value_enc, secret, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                    Ids.uuid(), applicationId, name, cipher.encrypt(value), secret ? 1 : 0, now, now);
        }
        audit.record(AuditActions.ENVIRONMENT_VARIABLE_CHANGED, "APPLICATION", applicationId, Map.of("name", name, "change", "set"));
    }

    @Transactional
    public void delete(String applicationId, String name) {
        jdbc.update("DELETE FROM environment_variables WHERE application_id = ? AND name = ?", applicationId, name);
        audit.record(AuditActions.ENVIRONMENT_VARIABLE_CHANGED, "APPLICATION", applicationId, Map.of("name", name, "change", "deleted"));
    }

    /** All variables, decrypted, for the runtime container. */
    public Map<String, String> runtimeEnvironment(String applicationId) {
        Map<String, String> m = new LinkedHashMap<>();
        for (EnvironmentVariable v : rows(applicationId)) m.put(v.name(), cipher.decrypt(v.valueEnc()));
        return m;
    }

    /** Non-secret variables only, for build arguments. */
    public Map<String, String> buildEnvironment(String applicationId) {
        Map<String, String> m = new LinkedHashMap<>();
        for (EnvironmentVariable v : rows(applicationId)) if (!v.secret()) m.put(v.name(), cipher.decrypt(v.valueEnc()));
        return m;
    }

    /** Masker covering every secret value of the application. */
    public SecretMasker masker(String applicationId) {
        return new SecretMasker(rows(applicationId).stream().filter(EnvironmentVariable::secret)
                .map(v -> cipher.decrypt(v.valueEnc())).toList());
    }

    private List<EnvironmentVariable> rows(String applicationId) {
        return jdbc.query("SELECT * FROM environment_variables WHERE application_id = ? ORDER BY name", MAPPER, applicationId);
    }
}
