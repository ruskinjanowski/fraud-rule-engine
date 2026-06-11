package com.capitech.fraud;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spins up a real Postgres and Kafka in Docker for full-context integration tests.
 *
 * <p>{@code @ServiceConnection} lets Spring Boot wire each container's connection
 * details into the application context automatically — no manual property plumbing.
 * Import this from any {@code @SpringBootTest} that needs the database or the broker.
 * Requires a running Docker daemon.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer<?> postgresContainer() {
		return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
	}

	/**
	 * {@code apache/kafka-native} (GraalVM-native build of the same broker, Testcontainers'
	 * recommended test image) — same broker and version as the compose stack's
	 * {@code apache/kafka}; only the packaging differs, and the native binary starts in
	 * well under a second. Must be 3.9.1+: the 3.9.0 images crash on boot under
	 * Testcontainers' wiring (the image's launch script passes a {@code -Dlog4j...} argument
	 * that the setup command mis-parses, failing storage format with a bogus
	 * "advertised.listeners cannot use 0.0.0.0" error).
	 */
	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1"));
	}
}
