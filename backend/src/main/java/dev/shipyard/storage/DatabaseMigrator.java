package dev.shipyard.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Minimal, dependency-free schema migrator. Applies classpath
 * {@code db/migration/V{n}__*.sql} files in order, once each.
 */
@Component
public class DatabaseMigrator implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);
    private static final Pattern NAME = Pattern.compile("V(\\d+)__.*\\.sql");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DatabaseMigrator(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL)");
        Integer current = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM schema_version", Integer.class);
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql");
        List<Resource> ordered = Arrays.stream(resources)
                .filter(r -> r.getFilename() != null && NAME.matcher(r.getFilename()).matches())
                .sorted(Comparator.comparingInt(DatabaseMigrator::version))
                .toList();
        for (Resource r : ordered) {
            int v = version(r);
            if (v <= current) continue;
            String sql = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.info("Applying migration {}", r.getFilename());
            tx.executeWithoutResult(s -> {
                for (String stmt : splitStatements(sql)) jdbc.execute(stmt);
                jdbc.update("INSERT INTO schema_version(version, name, applied_at) VALUES (?, ?, datetime('now'))", v, r.getFilename());
            });
        }
    }

    private static int version(Resource r) {
        Matcher m = NAME.matcher(r.getFilename());
        if (!m.matches()) return -1;
        return Integer.parseInt(m.group(1));
    }

    static List<String> splitStatements(String sql) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\n")) {
            String t = line.strip();
            if (t.startsWith("--")) continue;
            int idx = line.indexOf("--");
            cleaned.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return Arrays.stream(cleaned.toString().split(";"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
