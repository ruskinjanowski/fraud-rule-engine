package com.capitech.fraud.service;

import java.time.Instant;

/**
 * Filter criteria for listing stored evaluations (ADR-0005). All fields are optional and
 * combinable; {@code null} means "no constraint".
 *
 * @param customerId exact customer match
 * @param from       inclusive lower bound on the transaction's {@code occurredAt}
 * @param to         exclusive upper bound on the transaction's {@code occurredAt} — the
 *                   interval is half-open, {@code [from, to)}
 * @param flagged    restrict to flagged ({@code true}) or clean ({@code false}) evaluations
 */
public record EvaluationQuery(String customerId, Instant from, Instant to, Boolean flagged) {

	public EvaluationQuery {
		if (from != null && to != null && from.isAfter(to)) {
			throw new InvalidQueryException("'from' (%s) must not be after 'to' (%s)".formatted(from, to));
		}
	}
}
