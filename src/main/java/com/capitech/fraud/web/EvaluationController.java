package com.capitech.fraud.web;

import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.service.EvaluationQuery;
import com.capitech.fraud.service.FraudEvaluationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retrieval side of the brief's "retrieval of this data via an API" (ADR-0005):
 * a paginated list with combinable filters (customer, occurred-at time range, flagged),
 * and a by-event-id detail view carrying the full per-rule audit trail.
 */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

	/** Newest first; id breaks ties so pages are stable when timestamps collide. */
	private static final Sort NEWEST_FIRST =
			Sort.by(Sort.Order.desc("transactionEvent.occurredAt"), Sort.Order.desc("id"));

	private final FraudEvaluationService service;

	public EvaluationController(FraudEvaluationService service) {
		this.service = service;
	}

	@GetMapping
	public PagedModel<EvaluationSummaryResponse> list(
			@RequestParam(required = false) String customerId,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestParam(required = false) Boolean flagged,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		var query = new EvaluationQuery(customerId, from, to, flagged);
		Page<FraudEvaluation> result =
				service.findEvaluations(query, PageRequest.of(page, size, NEWEST_FIRST));
		return new PagedModel<>(result.map(EvaluationSummaryResponse::from));
	}

	@GetMapping("/{eventId}")
	public EvaluationResponse getByEventId(@PathVariable UUID eventId) {
		return EvaluationResponse.from(service.findByEventId(eventId));
	}
}
