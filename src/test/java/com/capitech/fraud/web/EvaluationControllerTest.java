package com.capitech.fraud.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.domain.TransactionCategory;
import com.capitech.fraud.domain.TransactionEvent;
import com.capitech.fraud.service.EvaluationNotFoundException;
import com.capitech.fraud.service.EvaluationQuery;
import com.capitech.fraud.service.FraudEvaluationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice for the paginated list endpoint (ADR-0005): parameter binding and
 * validation, query construction, and the summary/page response shape, with the service
 * mocked. Real filtering against Postgres is covered by
 * {@link com.capitech.fraud.FraudEvaluationFlowTest}.
 */
@WebMvcTest(EvaluationController.class)
class EvaluationControllerTest {

	private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private FraudEvaluationService service;

	@Test
	void listReturnsSummariesInPageEnvelope() throws Exception {
		given(service.findEvaluations(any(), any()))
				.willReturn(new PageImpl<>(List.of(flaggedEvaluation()), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/evaluations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].eventId").value(EVENT_ID.toString()))
				.andExpect(jsonPath("$.content[0].customerId").value("cust-1"))
				.andExpect(jsonPath("$.content[0].flagged").value(true))
				.andExpect(jsonPath("$.content[0].score").value(50))
				// summaries deliberately omit the per-rule trail — that's the detail view
				.andExpect(jsonPath("$.content[0].ruleResults").doesNotExist())
				.andExpect(jsonPath("$.page.totalElements").value(1))
				.andExpect(jsonPath("$.page.size").value(20))
				.andExpect(jsonPath("$.page.number").value(0));
	}

	@Test
	void listPassesFiltersAndPaginationToService() throws Exception {
		Instant from = Instant.parse("2026-06-10T00:00:00Z");
		Instant to = Instant.parse("2026-06-11T00:00:00Z");
		given(service.findEvaluations(any(), any()))
				.willReturn(new PageImpl<>(List.of(), PageRequest.of(1, 10), 0));

		mockMvc.perform(get("/api/evaluations")
						.param("customerId", "cust-1")
						.param("from", from.toString())
						.param("to", to.toString())
						.param("flagged", "true")
						.param("page", "1")
						.param("size", "10"))
				.andExpect(status().isOk());

		ArgumentCaptor<EvaluationQuery> query = ArgumentCaptor.forClass(EvaluationQuery.class);
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(service).findEvaluations(query.capture(), pageable.capture());
		assertThat(query.getValue()).isEqualTo(new EvaluationQuery("cust-1", from, to, true));
		assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
		assertThat(pageable.getValue().getSort().getOrderFor("transactionEvent.occurredAt"))
				.isNotNull()
				.extracting(Sort.Order::getDirection)
				.isEqualTo(Sort.Direction.DESC);
	}

	@Test
	void listRejectsOutOfBoundsPageAndSize() throws Exception {
		mockMvc.perform(get("/api/evaluations").param("size", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"))
				.andExpect(jsonPath("$.errors.size").exists());

		mockMvc.perform(get("/api/evaluations").param("size", "101"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/evaluations").param("page", "-1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.page").exists());

		verify(service, never()).findEvaluations(any(), any());
	}

	@Test
	void listRejectsFromAfterTo() throws Exception {
		mockMvc.perform(get("/api/evaluations")
						.param("from", "2026-06-11T00:00:00Z")
						.param("to", "2026-06-10T00:00:00Z"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid query parameters"));

		verify(service, never()).findEvaluations(any(), any());
	}

	@Test
	void listRejectsMalformedInstant() throws Exception {
		mockMvc.perform(get("/api/evaluations").param("from", "yesterday"))
				.andExpect(status().isBadRequest());

		verify(service, never()).findEvaluations(any(), any());
	}

	@Test
	void unknownEvaluationReturns404() throws Exception {
		given(service.findByEventId(EVENT_ID)).willThrow(new EvaluationNotFoundException(EVENT_ID));

		mockMvc.perform(get("/api/evaluations/" + EVENT_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Evaluation not found"));
	}

	private static FraudEvaluation flaggedEvaluation() {
		TransactionEvent event = new TransactionEvent(EVENT_ID, "txn-1", "cust-1", new BigDecimal("25000.00"),
				"ZAR", TransactionCategory.ONLINE, "ACME Online", "ZA", Instant.parse("2026-06-11T02:30:00Z"));
		return new FraudEvaluation(event, true, 50);
	}
}
