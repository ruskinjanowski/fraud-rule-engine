package com.capitech.fraud.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.repository.FraudEvaluationRepository;
import com.capitech.fraud.repository.TransactionEventRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Round-trips the {@code TransactionEvent} → {@code FraudEvaluation} → {@code RuleResult}
 * aggregate against a real Postgres. Flyway builds the schema and Hibernate runs in
 * {@code validate} mode, so this also proves the entities match the migration (ADR-0001).
 */
@DataJpaTest(properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TransactionPersistenceTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	@Autowired
	private TransactionEventRepository events;

	@Autowired
	private FraudEvaluationRepository evaluations;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	void persistsEventEvaluationAndRuleResults() {
		UUID eventId = UUID.randomUUID();
		TransactionEvent event = new TransactionEvent(eventId, "txn-1", "cust-1",
				new BigDecimal("15000.0000"), "ZAR", TransactionCategory.ONLINE,
				"ACME Online", "ZA", Instant.parse("2026-06-11T02:30:00Z"));
		events.save(event);

		FraudEvaluation evaluation = new FraudEvaluation(event, true, 80);
		evaluation.addRuleResult(new RuleResult("AMOUNT_THRESHOLD", "1", true, 80, "amount 15000 > 10000"));
		evaluation.addRuleResult(new RuleResult("ODD_HOURS", "1", false, 0, null));
		evaluations.save(evaluation);

		// Force a real DB round-trip rather than reading from the persistence-context cache.
		entityManager.flush();
		entityManager.clear();

		FraudEvaluation reloaded = evaluations.findByTransactionEvent_EventId(eventId).orElseThrow();
		assertThat(reloaded.isFlagged()).isTrue();
		assertThat(reloaded.getScore()).isEqualTo(80);
		assertThat(reloaded.getEvaluatedAt()).isNotNull();
		assertThat(reloaded.getRuleResults()).hasSize(2);
		assertThat(reloaded.getRuleResults())
				.extracting(RuleResult::getRuleCode)
				.containsExactlyInAnyOrder("AMOUNT_THRESHOLD", "ODD_HOURS");

		TransactionEvent reloadedEvent = reloaded.getTransactionEvent();
		assertThat(reloadedEvent.getEventId()).isEqualTo(eventId);
		assertThat(reloadedEvent.getAmount()).isEqualByComparingTo("15000.0000");
		assertThat(reloadedEvent.getCategory()).isEqualTo(TransactionCategory.ONLINE);
		assertThat(reloadedEvent.getReceivedAt()).isNotNull();

		assertThat(events.existsByEventId(eventId)).isTrue();
		assertThat(events.existsByEventId(UUID.randomUUID())).isFalse();
	}
}
