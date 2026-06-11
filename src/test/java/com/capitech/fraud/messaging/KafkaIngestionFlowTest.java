package com.capitech.fraud.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.capitech.fraud.TestcontainersConfiguration;
import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.repository.FraudEvaluationRepository;
import com.capitech.fraud.service.FraudEvaluationService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * End-to-end ingestion over the primary path (ADR-0004): publish JSON to the transactions
 * topic against a real broker (Testcontainers Kafka), and assert the event is evaluated,
 * stored, and retrievable — the same brief requirements {@code FraudEvaluationFlowTest}
 * exercises over HTTP, now over Kafka. Also proves un-processable records are dead-lettered
 * without wedging the partition.
 *
 * <p>Each test uses its own customer so stateful rules don't leak history between scenarios.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KafkaIngestionFlowTest {

	// Raw type: the autoconfigured bean is KafkaTemplate<?, ?>, so a parameterized field
	// would match no bean. The field name resolves it to the "kafkaTemplate" bean.
	@Autowired
	@SuppressWarnings("rawtypes")
	private KafkaTemplate kafkaTemplate;

	@Autowired
	private FraudEvaluationService service;

	@Autowired
	private FraudEvaluationRepository evaluations;

	@Value("${fraud.kafka.transactions-topic}")
	private String topic;

	@Test
	@SuppressWarnings("unchecked")
	void validEventIsEvaluatedStoredAndRetrievable() {
		UUID eventId = UUID.randomUUID();
		// 25000 is over the amount threshold (10000) → flagged with score 50.
		kafkaTemplate.send(topic, eventId.toString(), eventJson(eventId, "25000.00"));

		await().atMost(Duration.ofSeconds(20)).ignoreExceptions().untilAsserted(() -> {
			FraudEvaluation evaluation = service.findByEventId(eventId);
			assertThat(evaluation.isFlagged()).isTrue();
			assertThat(evaluation.getScore()).isEqualTo(50);
		});
	}

	@Test
	@SuppressWarnings("unchecked")
	void malformedJsonIsDeadLetteredAndConsumerKeepsProcessing() {
		String marker = UUID.randomUUID().toString();
		kafkaTemplate.send(topic, marker, "{\"not valid json\": \"" + marker); // truncated → deserialization fails

		// The bad record lands on the DLT with its original payload preserved...
		assertDeadLetteredRecordContains(marker);

		// ...and the partition keeps moving: a valid event behind it is still processed.
		UUID eventId = UUID.randomUUID();
		kafkaTemplate.send(topic, eventId.toString(), eventJson(eventId, "25000.00"));
		await().atMost(Duration.ofSeconds(20)).ignoreExceptions()
				.untilAsserted(() -> assertThat(service.findByEventId(eventId).isFlagged()).isTrue());
	}

	@Test
	@SuppressWarnings("unchecked")
	void eventFailingValidationIsDeadLettered() {
		UUID eventId = UUID.randomUUID();
		// Negative amount violates @Positive — deserializes cleanly but fails Bean Validation.
		kafkaTemplate.send(topic, eventId.toString(), eventJson(eventId, "-5.00"));

		// Dead-lettered (the original JSON carries its eventId); never stored.
		assertDeadLetteredRecordContains(eventId.toString());
		assertThat(evaluations.findByTransactionEvent_EventId(eventId)).isEmpty();
	}

	private String eventJson(UUID eventId, String amount) {
		return """
				{
				  "eventId": "%s",
				  "transactionId": "txn-%s",
				  "customerId": "cust-%s",
				  "amount": %s,
				  "currency": "ZAR",
				  "category": "ONLINE",
				  "merchant": "ACME Online",
				  "country": "ZA",
				  "occurredAt": "2026-06-11T10:00:00Z"
				}
				""".formatted(eventId, eventId, UUID.randomUUID(), amount);
	}

	/** Polls {@code <topic>-dlt} and asserts a record whose payload contains the marker arrives. */
	private void assertDeadLetteredRecordContains(String marker) {
		Map<String, Object> props = KafkaTestUtils.consumerProps(
				bootstrapServers(), "dlt-test-" + UUID.randomUUID(), "true");
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

		List<String> seen = new ArrayList<>();
		try (Consumer<String, String> consumer =
				new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
			consumer.subscribe(List.of(topic + "-dlt"));
			await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
				consumer.poll(Duration.ofMillis(500)).forEach(record -> seen.add(record.value()));
				assertThat(seen).anyMatch(value -> value != null && value.contains(marker));
			});
		}
	}

	private String bootstrapServers() {
		Object value = kafkaTemplate.getProducerFactory().getConfigurationProperties()
				.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
		return value instanceof List<?> list ? String.join(",", list.stream().map(Object::toString).toList())
				: value.toString();
	}
}
