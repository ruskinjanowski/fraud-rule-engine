package com.capitech.fraud.web;

import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.service.EvaluationQuery;
import com.capitech.fraud.service.FraudEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ProblemDetail;
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
@Tag(name = "Evaluations")
public class EvaluationController {

	/** Newest first; id breaks ties so pages are stable when timestamps collide. */
	private static final Sort NEWEST_FIRST =
			Sort.by(Sort.Order.desc("transactionEvent.occurredAt"), Sort.Order.desc("id"));

	private final FraudEvaluationService service;

	public EvaluationController(FraudEvaluationService service) {
		this.service = service;
	}

	@Operation(
			summary = "List fraud evaluations",
			description = "Newest first. The customer, time-range and flagged filters combine (logical AND); "
					+ "omit a filter to leave that dimension unconstrained.")
	@GetMapping
	public PagedModel<EvaluationSummaryResponse> list(
			@Parameter(description = "Restrict to a single customer's transactions.")
			@RequestParam(required = false) String customerId,
			@Parameter(description = "Inclusive lower bound on the transaction's occurrence time (ISO-8601 instant, UTC).")
			@RequestParam(required = false) Instant from,
			@Parameter(description = "Exclusive upper bound on the transaction's occurrence time (ISO-8601 instant, UTC).")
			@RequestParam(required = false) Instant to,
			@Parameter(description = "Return only flagged (true) or only unflagged (false) evaluations.")
			@RequestParam(required = false) Boolean flagged,
			@Parameter(description = "Zero-based page index.")
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@Parameter(description = "Page size (1–100).")
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		var query = new EvaluationQuery(customerId, from, to, flagged);
		Page<FraudEvaluation> result =
				service.findEvaluations(query, PageRequest.of(page, size, NEWEST_FIRST));
		return new PagedModel<>(result.map(EvaluationSummaryResponse::from));
	}

	@Operation(
			summary = "Get an evaluation by event id",
			description = "Returns the full decision for one transaction event, including the per-rule audit trail.")
	@ApiResponse(responseCode = "200", description = "Evaluation found")
	@ApiResponse(responseCode = "404", description = "No evaluation exists for the given event id",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping("/{eventId}")
	public EvaluationResponse getByEventId(
			@Parameter(description = "The transaction event's UUID.") @PathVariable UUID eventId) {
		return EvaluationResponse.from(service.findByEventId(eventId));
	}
}
