package dev.shipyard.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfig {

    @Bean
    DataSource dataSource(StorageLayout layout) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + layout.database().resolve("shipyard.db"));
        cfg.setDriverClassName("org.sqlite.JDBC");
        // SQLite allows one writer at a time; WAL lets readers proceed concurrently.
        cfg.setMaximumPoolSize(4);
        cfg.addDataSourceProperty("journal_mode", "WAL");
        cfg.addDataSourceProperty("busy_timeout", "15000");
        cfg.addDataSourceProperty("foreign_keys", "true");
        cfg.addDataSourceProperty("synchronous", "NORMAL");
        cfg.setPoolName("shipyard-sqlite");
        return new HikariDataSource(cfg);
    }
}
