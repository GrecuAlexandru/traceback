package dev.traceback.deployment;

import java.util.Map;
import org.flywaydb.core.Flyway;

/** One-shot release operation: no HTTP, storage recovery, schedulers or model clients. */
public final class DatabaseMigration {
    private DatabaseMigration() {}
    public static void validateRuntime(javax.sql.DataSource datasource) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(datasource);
        Long versions = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1','2','3','4','5','6','7','8')", Long.class);
        String latest = jdbc.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class);
        if (versions == null || versions != 8 || !"8".equals(latest))
            throw new IllegalStateException("Expected deployment schema is not installed");
        // Validation is read-only. Runtime credentials never run migrate/clean/repair.
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").cleanDisabled(true).load().validate();
    }
    public static void run(Map<String, String> environment) {
        String url = required(environment, "DATABASE_URL");
        if ("production".equals(environment.get("TRACEBACK_ENVIRONMENT"))) ProductionConfiguration.requireDatabaseTls(url);
        Flyway.configure().dataSource(url, required(environment, "MIGRATION_USER"), required(environment, "MIGRATION_PASSWORD"))
                .locations("classpath:db/migration").cleanDisabled(true).validateOnMigrate(true)
                .lockRetryCount(30).initSql("SET lock_timeout = '5s'; SET statement_timeout = '60s'")
                .load().migrate();
    }
    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing migration configuration: " + name);
        return value;
    }
}
