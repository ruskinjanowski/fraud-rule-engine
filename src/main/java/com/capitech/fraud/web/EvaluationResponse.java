package com.capitech.fraud.web;

import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.domain.RuleResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API representation of a stored {@link FraudEvaluation}, including the per-rule audit
 * trail. Returned by {@code GET /api/evaluations/{eventId}}.
 */
@Schema(description = "A fraud evaluation with its full per-rule audit trail.")
public record EvaluationResponse(
		@Schema(description = "The evaluated transaction event's id.") UUID eventId,
		@Schema(description = "Producer-supplied transaction reference.") String transactionId,
		String customerId,
		@Schema(description = "True once the summed rule score reaches the flag threshold.") boolean flagged,
		@Schema(description = "Summed score of all hitting rules.") int score,
		Instant evaluatedAt,
		@Schema(description = "One entry per rule the engine ran.") List<RuleResultResponse> ruleResults) {

	public static EvaluationResponse from(FraudEvaluation evaluation) {
		var event = evaluation.getTransactionEvent();
		List<RuleResultResponse> rules = evaluation.getRuleResults().stream()
				.map(RuleResultResponse::from)
				.toList();
		return new EvaluationResponse(
				event.getEventId(),
				event.getTransactionId(),
				event.getCustomerId(),
				evaluation.isFlagged(),
				evaluation.getScore(),
				evaluation.getEvaluatedAt(),
				rules);
	}

	/** One rule's contribution to the evaluation. */
	@Schema(description = "One rule's contribution to the evaluation.")
	public record RuleResultResponse(
			@Schema(description = "Stable rule identifier, e.g. AMOUNT_THRESHOLD.") String ruleCode,
			String ruleVersion,
			@Schema(description = "Whether this rule matched the transaction.") boolean hit,
			@Schema(description = "Score this rule contributed (0 when it did not hit).") int score,
			@Schema(description = "Human-readable explanation of the outcome.") String detail) {

		static RuleResultResponse from(RuleResult result) {
			return new RuleResultResponse(result.getRuleCode(), result.getRuleVersion(),
					result.isHit(), result.getScore(), result.getDetail());
		}
	}
}
