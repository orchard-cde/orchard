package dev.orchard.roots;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void postgres_freshMigrate() throws SQLException {
        assertFreshMigrate(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    void h2_freshMigrate() throws SQLException {
        assertFreshMigrate("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static void assertFreshMigrate(String url, String user, String password) throws SQLException {
        var result = Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(query(url, user, password, "SELECT is_nullable FROM information_schema.columns"
            + " WHERE LOWER(table_name) = 'groves' AND LOWER(column_name) = 'plot_json'"))
            .containsExactly("NO");
        assertThat(query(url, user, password, "SELECT column_name FROM information_schema.columns"
            + " WHERE LOWER(column_name) LIKE 'seedling\\_%'"))
            .isEmpty();
        assertThat(query(url, user, password, "SELECT is_nullable FROM information_schema.columns"
            + " WHERE LOWER(table_name) = 'groves' AND LOWER(column_name) = 'seed_spec'"))
            .containsExactly("NO");
        assertThat(query(url, user, password, "SELECT LOWER(data_type) FROM information_schema.columns"
            + " WHERE LOWER(table_name) IN ('bees', 'prebuilds') AND LOWER(column_name) LIKE '%\\_at'"))
            .hasSize(5)
            .allMatch(type -> type.equals("timestamp with time zone"));
        assertDeletingGroveDeletesItsBees(url, user, password);
    }

    private static void assertDeletingGroveDeletesItsBees(String url, String user, String password)
            throws SQLException {
        UUID cultivator = UUID.randomUUID();
        UUID grove = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO cultivators (id, username, email, created_at, last_active_at)"
                + " VALUES ('" + cultivator + "', 'u-" + cultivator + "', 'e-" + cultivator + "',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            s.executeUpdate("INSERT INTO groves (id, cultivator_id, name, repository_url, branch, state,"
                + " planted_at, last_accessed_at, plot_json) VALUES ('" + grove + "', '" + cultivator + "',"
                + " 'g', 'https://example.invalid/r.git', 'main', 'CLEARED', CURRENT_TIMESTAMP,"
                + " CURRENT_TIMESTAMP, '{}')");
            s.executeUpdate("INSERT INTO bees (id, grove_id, type, state, spec_type, hatched_at) VALUES ('"
                + UUID.randomUUID() + "', '" + grove + "', 'OPENCODE', 'SMOKED', 'DEFAULT', CURRENT_TIMESTAMP)");
            s.executeUpdate("DELETE FROM groves WHERE id = '" + grove + "'");
        }
        assertThat(query(url, user, password, "SELECT id FROM bees WHERE grove_id = '" + grove + "'")).isEmpty();
    }

    private static List<String> query(String url, String user, String password, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
            return rows;
        }
    }
}
