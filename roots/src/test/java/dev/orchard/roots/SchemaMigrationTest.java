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
