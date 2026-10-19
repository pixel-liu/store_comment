package com.foodiego;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests.
 * <p>
 * Starts singleton Testcontainers (MySQL 5.7, Redis 7-alpine, RabbitMQ 3.9-management)
 * and injects dynamic connection properties into the Spring context.
 * <p>
 * Uses {@link ApplicationContextInitializer} instead of {@code @DynamicPropertySource}
 * because Spring Boot 2.3.12 does not support the latter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:application-test.yaml")
@ContextConfiguration(initializers = AbstractIntegrationTest.TestContainerInitializer.class)
public abstract class AbstractIntegrationTest {

    // ──────────────────── Singleton containers ────────────────────

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:5.7")
            .withDatabaseName("foodiego")
            .withUsername("root")
            .withPassword("123456")
            .withInitScript("db/hmdp.sql");

    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "123456")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.9-management-alpine"))
            .withAdminPassword("guest");

    static {
        MYSQL.start();
        REDIS.start();
        RABBITMQ.start();
    }

    // ──────────────────── Initializer ────────────────────

    /**
     * Injects dynamic Testcontainers ports into the Spring environment.
     * This is the pre-Spring-Boot-2.4 equivalent of {@code @DynamicPropertySource}.
     */
    static class TestContainerInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(ctx,
                    "spring.datasource.url=" + MYSQL.getJdbcUrl()
                            + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                    "spring.datasource.username=" + MYSQL.getUsername(),
                    "spring.datasource.password=" + MYSQL.getPassword(),
                    "spring.redis.host=" + REDIS.getHost(),
                    "spring.redis.port=" + REDIS.getMappedPort(6379),
                    "spring.redis.password=123456",
                    "spring.rabbitmq.host=" + RABBITMQ.getHost(),
                    "spring.rabbitmq.port=" + RABBITMQ.getMappedPort(5672),
                    "spring.rabbitmq.username=guest",
                    "spring.rabbitmq.password=guest"
            );
        }
    }
}
