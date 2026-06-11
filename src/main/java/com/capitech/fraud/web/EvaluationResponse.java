package com.capitech.fraud.web;

import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.domain.RuleResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API representation of a stored {@link FraudEvaluation}, including the per-rule audit
 * trail. Returned by {@code GET /api/evaluations/{eventId}}.
 */
public record EvaluationResponse(
		UUID eventId,
		String transactionId,
		String customerId,
		boolean flagged,
		int score,
		Instant evaluatedAt,
		List<RuleResultResponse> ruleResults) {

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
	public record RuleResultResponse(String ruleCode, String ruleVersion, boolean hit, int score, String detail) {

		static RuleResultResponse from(RuleResult result) {
			return new RuleResultResponse(result.getRuleCode(), result.getRuleVersion(),
					result.isHit(), result.getScore(), result.getDetail());
		}
	}
}
