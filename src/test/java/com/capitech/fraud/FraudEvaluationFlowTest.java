package com.capitech.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capitech.fraud.domain.TransactionCategory;
import com.capitech.fraud.domain.TransactionEvent;
import com.capitech.fraud.service.FraudEvaluationService;
import com.capitech.fraud.service.FraudEvaluationService.Result;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end persistence-and-retrieval path against a real Postgres (Testcontainers):
 * ingest a transaction through {@link FraudEvaluationService} — the same seam the Kafka
 * consumer drives (ingestion is Kafka-only; ADR-0004) — have it evaluated and stored, then
 * retrieve the decision over the query API. Exercises the brief's four functional
 * requirements without an HTTP write path (ADR-0002, ADR-0003). The ingestion transport
 * itself (the transactions topic) is covered by
 * {@link com.capitech.fraud.messaging.KafkaIngestionFlowTest}.
 *
 * <p>Each test uses its own customer: stateful rules read the customer's stored history, so
 * sharing one customer across tests would leak state between scenarios.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FraudEvaluationFlowTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FraudEvaluationService service;

	@Test
	void ingestAboveThresholdIsFlaggedAndRetrievable() throws Exception {
		UUID eventId = UUID.randomUUID();

		Result result = ingest(eventId, uniqueCustomer(), "25000.00", "2026-06-11T10:00:00Z");
		assertThat(result.created()).isTrue();
		assertThat(result.evaluation().isFlagged()).isTrue();
		assertThat(result.evaluation().getScore()).isEqualTo(50);

		mockMvc.perform(get("/api/evaluations/" + eventId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventId").value(eventId.toString()))
				.andExpect(jsonPath("$.flagged").value(true))
				.andExpect(jsonPath("$.score").value(50))
				.andExpect(jsonPath("$.ruleResults[0].ruleCode").value("AMOUNT_THRESHOLD"))
				.andExpect(jsonPath("$.ruleResults[0].hit").value(true));
	}

	@Test
	void ingestBelowThresholdIsNotFlagged() throws Exception {
		UUID eventId = UUID.randomUUID();

		Result result = ingest(eventId, uniqueCustomer(), "100.00", "2026-06-11T10:00:00Z");
		assertThat(result.evaluation().isFlagged()).isFalse();
		assertThat(result.evaluation().getScore()).isZero();

		mockMvc.perform(get("/api/evaluations/" + eventId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.flagged").value(false))
				.andExpect(jsonPath("$.score").value(0));
	}

	/**
	 * The corroboration story from docs/rules.md §5: no single rule is strong enough, but
	 * a fourth small payment within ten minutes (VELOCITY, 30) deep in the night window
	 * (ODD_HOURS, 20) crosses the flag threshold together.
	 */
	@Test
	void corroboratingWeakSignalsFlagTogether() throws Exception {
		String customerId = uniqueCustomer();
		// 00:0xZ = 02:0x SAST — inside the night window. Distinct amounts keep
		// DUPLICATE_TRANSACTION and CARD_TESTING out of the picture.
		ingest(UUID.randomUUID(), customerId, "910.00", "2026-06-11T00:00:00Z");
		ingest(UUID.randomUUID(), customerId, "920.00", "2026-06-11T00:02:00Z");
		Result third = ingest(UUID.randomUUID(), customerId, "930.00", "2026-06-11T00:04:00Z");
		assertThat(third.evaluation().isFlagged()).isFalse();

		UUID fourthId = UUID.randomUUID();
		Result fourth = ingest(fourthId, customerId, "955.00", "2026-06-11T00:08:00Z");
		assertThat(fourth.evaluation().isFlagged()).isTrue();
		assertThat(fourth.evaluation().getScore()).isEqualTo(50);

		// Nine rule results — the eight deterministic rules plus the advisory ANOMALY_SCORE.
		// The score stays 50: the advisory rule is recorded but never counted (ADR-0006).
		mockMvc.perform(get("/api/evaluations/" + fourthId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.flagged").value(true))
				.andExpect(jsonPath("$.score").value(50))
				.andExpect(jsonPath("$.ruleResults.length()").value(9));
	}

	@Test
	void replayingSameEventIdIsIdempotent() throws Exception {
		UUID eventId = UUID.randomUUID();
		String customerId = uniqueCustomer();

		assertThat(ingest(eventId, customerId, "25000.00", "2026-06-11T10:00:00Z").created()).isTrue();
		// Replay returns the already-stored evaluation rather than re-processing it.
		assertThat(ingest(eventId, customerId, "25000.00", "2026-06-11T10:00:00Z").created()).isFalse();

		mockMvc.perform(get("/api/evaluations/" + eventId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.score").value(50));
	}

	/**
	 * The list endpoint's filters against real data (ADR-0005): by customer, half-open
	 * occurred-at time range, and flagged-only — each narrowing the same three stored
	 * evaluations (one flagged by AMOUNT_THRESHOLD, two clean, hours apart).
	 */
	@Test
	void listFiltersByCustomerTimeRangeAndFlag() throws Exception {
		String customerId = uniqueCustomer();
		UUID flaggedId = UUID.randomUUID();
		ingest(flaggedId, customerId, "25000.00", "2026-06-10T10:00:00Z");
		ingest(UUID.randomUUID(), customerId, "100.00", "2026-06-10T12:00:00Z");
		ingest(UUID.randomUUID(), customerId, "200.00", "2026-06-11T10:00:00Z");

		// by customer alone: all three, newest first, summary shape (no rule trail)
		mockMvc.perform(get("/api/evaluations").param("customerId", customerId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.totalElements").value(3))
				.andExpect(jsonPath("$.content[0].occurredAt").value("2026-06-11T10:00:00Z"))
				.andExpect(jsonPath("$.content[0].ruleResults").doesNotExist());

		// half-open window [10:00, 12:00): keeps the 10:00 event, excludes the one at 'to'
		mockMvc.perform(get("/api/evaluations")
						.param("customerId", customerId)
						.param("from", "2026-06-10T10:00:00Z")
						.param("to", "2026-06-10T12:00:00Z"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].eventId").value(flaggedId.toString()));

		// flagged only: just the amount-threshold hit
		mockMvc.perform(get("/api/evaluations")
						.param("customerId", customerId)
						.param("flagged", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].eventId").value(flaggedId.toString()))
				.andExpect(jsonPath("$.content[0].flagged").value(true))
				.andExpect(jsonPath("$.content[0].score").value(50));
	}

	@Test
	void listPaginatesNewestFirst() throws Exception {
		String customerId = uniqueCustomer();
		ingest(UUID.randomUUID(), customerId, "100.00", "2026-06-10T10:00:00Z");
		ingest(UUID.randomUUID(), customerId, "200.00", "2026-06-10T12:00:00Z");
		ingest(UUID.randomUUID(), customerId, "300.00", "2026-06-10T14:00:00Z");

		mockMvc.perform(get("/api/evaluations").param("customerId", customerId).param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].occurredAt").value("2026-06-10T14:00:00Z"))
				.andExpect(jsonPath("$.content[1].occurredAt").value("2026-06-10T12:00:00Z"))
				.andExpect(jsonPath("$.page.totalElements").value(3))
				.andExpect(jsonPath("$.page.totalPages").value(2));

		mockMvc.perform(get("/api/evaluations")
						.param("customerId", customerId).param("size", "2").param("page", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].occurredAt").value("2026-06-10T10:00:00Z"))
				.andExpect(jsonPath("$.page.number").value(1));
	}

	@Test
	void retrievingUnknownEvaluationReturns404() throws Exception {
		mockMvc.perform(get("/api/evaluations/" + UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Evaluation not found"));
	}

	private Result ingest(UUID eventId, String customerId, String amount, String occurredAt) {
		TransactionEvent event = new TransactionEvent(eventId, "txn-" + eventId, customerId,
				new BigDecimal(amount), "ZAR", TransactionCategory.ONLINE, "ACME Online", "ZA",
				Instant.parse(occurredAt));
		return service.ingestAndEvaluate(event);
	}

	private static String uniqueCustomer() {
		return "cust-" + UUID.randomUUID();
	}
}
