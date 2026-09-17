package com.metropulse.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need the real PostgreSQL/PostGIS behaviour the application relies on.
 *
 * <p>H2 cannot prove PostGIS geometry, {@code ON CONFLICT} or {@code jsonb} behaviour, so the whole
 * Flyway migration set is applied to a real PostGIS database that is shared by every subclass.
 *
 * <p>By default that database is a throwaway Testcontainers container. When
 * {@code METROPULSE_TEST_DB_URL} (plus optional {@code METROPULSE_TEST_DB_USERNAME} and
 * {@code METROPULSE_TEST_DB_PASSWORD}) is set, the tests run against that PostGIS database instead.
 * That escape hatch exists for hosts where docker-java cannot talk to the local Docker Engine; CI
 * runs the container path.
 *
 * <p>Outbox publishing is disabled because these tests assert on stored rows, not on Kafka delivery.
 */
@SpringBootTest
public abstract class PostgisIntegrationTest {

    private static final String EXTERNAL_URL = System.getenv("METROPULSE_TEST_DB_URL");

    private static PostgreSQLContainer<?> postgis;

    private static String jdbcUrl;
    private static String username;
    private static String password;

    static {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            jdbcUrl = EXTERNAL_URL;
            username = envOrDefault("METROPULSE_TEST_DB_USERNAME", "metropulse");
            password = envOrDefault("METROPULSE_TEST_DB_PASSWORD", "metropulse");
        } else {
            postgis = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("metropulse")
                    .withUsername("metropulse")
                    .withPassword("metropulse");
            postgis.start();
            jdbcUrl = postgis.getJdbcUrl();
            username = postgis.getUsername();
            password = postgis.getPassword();
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.clean-disabled", () -> false);
        registry.add("metropulse.outbox.enabled", () -> false);
        registry.add("metropulse.telemetry.ingest-key", () -> "test-ingest-key");
    }
}
