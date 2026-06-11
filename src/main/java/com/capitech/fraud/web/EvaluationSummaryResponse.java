package com.capitech.fraud.web;

import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.domain.TransactionCategory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the paginated list endpoint, {@code GET /api/evaluations}: the transaction's
 * identity and the decision, without the per-rule audit trail. The full trail is the
 * detail view, {@code GET /api/evaluations/{eventId}} ({@link EvaluationResponse}).
 */
public record EvaluationSummaryResponse(
		UUID eventId,
		String transactionId,
		String customerId,
		BigDecimal amount,
		String currency,
		TransactionCategory category,
		boolean flagged,
		int score,
		Instant occurredAt,
		Instant evaluatedAt) {

	public static EvaluationSummaryResponse from(FraudEvaluation evaluation) {
		var event = evaluation.getTransactionEvent();
		return new EvaluationSummaryResponse(
				event.getEventId(),
				event.getTransactionId(),
				event.getCustomerId(),
				event.getAmount(),
				event.getCurrency(),
				event.getCategory(),
				evaluation.isFlagged(),
				evaluation.getScore(),
				event.getOccurredAt(),
				evaluation.getEvaluatedAt());
	}
}
