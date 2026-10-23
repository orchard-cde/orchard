package dev.orchard.roots;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class V10MigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void cleanDatabase() {
        flyway(null).cleanDisabled(false).load().clean();
    }

    @Test
    void emptyDatabase_migratesAndHasPlotJsonNotNull() throws SQLException {
        flyway(null).load().migrate();

        assertThat(query("SELECT is_nullable FROM information_schema.columns"
            + " WHERE table_name = 'groves' AND column_name = 'plot_json'")).containsExactly("NO");
        assertThat(query("SELECT column_name FROM information_schema.columns"
            + " WHERE table_name = 'groves' AND column_name LIKE 'seedling_%'")).isEmpty();
    }

    @Test
    void clearedGroveWithBee_isDeleted() throws SQLException {
        flyway("9").load().migrate();
        UUID cultivator = insertCultivator();
        UUID grove = insertGrove(cultivator, "CLEARED");
        insertBee(grove);

        flyway(null).load().migrate();

        assertThat(query("SELECT id::text FROM groves")).isEmpty();
        assertThat(query("SELECT id::text FROM bees")).isEmpty();
    }

    @Test
    void liveGrove_refusesAndRollsBack() throws SQLException {
        flyway("9").load().migrate();
        UUID cultivator = insertCultivator();
        insertGrove(cultivator, "FLOURISHING");
        insertGrove(cultivator, "CLEARED");

        assertThatThrownBy(() -> flyway(null).load().migrate())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("groves remain");

        assertThat(query("SELECT column_name FROM information_schema.columns"
            + " WHERE table_name = 'groves' AND column_name = 'seedling_id'")).containsExactly("seedling_id");
        assertThat(query("SELECT state FROM groves ORDER BY state"))
            .containsExactly("CLEARED", "FLOURISHING");
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String target) {
        var config = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration");
        return target == null ? config : config.target(target);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static List<String> query(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
            return rows;
        }
    }

    private static UUID insertCultivator() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO cultivators (id, username, email, created_at, last_active_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "u-" + id);
            ps.setString(3, id + "@example.com");
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID insertGrove(UUID cultivator, String state) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO groves (id, cultivator_id, name, repository_url, branch, state, planted_at,"
                    + " last_accessed_at, seedling_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, cultivator);
            ps.setString(3, "g-" + state);
            ps.setString(4, "https://github.com/test/repo");
            ps.setString(5, "main");
            ps.setString(6, state);
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.setTimestamp(8, Timestamp.from(Instant.now()));
            ps.setObject(9, UUID.randomUUID());
            ps.executeUpdate();
        }
        return id;
    }

    private static void insertBee(UUID grove) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO bees (id, grove_id, type, state, spec_type, hatched_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, grove);
            ps.setString(3, "OPENCODE");
            ps.setString(4, "STOPPED");
            ps.setString(5, "OPENCODE");
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }
}
