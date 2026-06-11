package com.capitech.fraud.service;

import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.domain.TransactionEvent;
import com.capitech.fraud.metrics.FraudMetrics;
import com.capitech.fraud.repository.FraudEvaluationRepository;
import com.capitech.fraud.repository.TransactionEventRepository;
import com.capitech.fraud.rule.CustomerHistory;
import com.capitech.fraud.rule.RuleEngine;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.criteria.Predicate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the ingest-and-evaluate pipeline and its transaction boundary (ADR-0002).
 *
 * <p>The Kafka consumer is a thin adapter over {@link #ingestAndEvaluate(TransactionEvent)}:
 * the processing logic lives here once, behind the entry point. The same seam backs the
 * retrieval read methods used by the HTTP query API.
 */
@Service
public class FraudEvaluationService {

	private final TransactionEventRepository events;
	private final FraudEvaluationRepository evaluations;
	private final RuleEngine ruleEngine;
	private final FraudMetrics metrics;

	public FraudEvaluationService(TransactionEventRepository events,
			FraudEvaluationRepository evaluations, RuleEngine ruleEngine, FraudMetrics metrics) {
		this.events = events;
		this.evaluations = evaluations;
		this.ruleEngine = ruleEngine;
		this.metrics = metrics;
	}

	/**
	 * Persists the event, runs the rule engine, and stores the resulting evaluation —
	 * all in one transaction.
	 *
	 * <p>Idempotent on {@code eventId}: a replayed event returns its existing evaluation
	 * unchanged rather than re-evaluating or double-storing. This honours the unique
	 * constraints from ADR-0001 and absorbs Kafka's at-least-once redelivery later.
	 */
	@Transactional
	public Result ingestAndEvaluate(TransactionEvent event) {
		UUID eventId = event.getEventId();
		FraudEvaluation existing = evaluations.findByTransactionEvent_EventId(eventId).orElse(null);
		if (existing != null) {
			return new Result(existing, false);
		}

		Timer.Sample sample = metrics.startEvaluation();
		events.save(event);
		FraudEvaluation evaluation = ruleEngine.evaluate(event, loadHistory(event));
		evaluations.save(evaluation);
		metrics.recordEvaluation(sample, evaluation);
		return new Result(evaluation, true);
	}

	/**
	 * One history query per evaluation, covering the largest window any enabled rule needs
	 * (ADR-0003). Anchored at the event's {@code occurredAt} and excluding the event itself,
	 * which was persisted just above in the same transaction.
	 */
	private CustomerHistory loadHistory(TransactionEvent event) {
		Duration lookback = ruleEngine.requiredLookback();
		Instant anchor = event.getOccurredAt();
		if (lookback.isZero()) {
			return CustomerHistory.none(anchor);
		}
		return new CustomerHistory(anchor,
				events.findByCustomerIdAndEventIdNotAndOccurredAtBetweenOrderByOccurredAtDesc(
						event.getCustomerId(), event.getEventId(), anchor.minus(lookback), anchor));
	}

	/** Read side of the retrieval API: fetch a stored evaluation by its event's id. */
	@Transactional(readOnly = true)
	public FraudEvaluation findByEventId(UUID eventId) {
		return evaluations.findByTransactionEvent_EventId(eventId)
				.orElseThrow(() -> new EvaluationNotFoundException(eventId));
	}

	/**
	 * List side of the retrieval API (ADR-0005): evaluations matching the query's filters,
	 * one page at a time. Filters compose as criteria predicates so any combination of
	 * customer, time range, and flag works without a query method per permutation.
	 */
	@Transactional(readOnly = true)
	public Page<FraudEvaluation> findEvaluations(EvaluationQuery query, Pageable pageable) {
		return evaluations.findAll(matching(query), pageable);
	}

	private static Specification<FraudEvaluation> matching(EvaluationQuery query) {
		return (root, cq, cb) -> {
			var event = root.get("transactionEvent");
			var predicates = new ArrayList<Predicate>();
			if (query.customerId() != null) {
				predicates.add(cb.equal(event.get("customerId"), query.customerId()));
			}
			if (query.from() != null) {
				predicates.add(cb.greaterThanOrEqualTo(event.<Instant>get("occurredAt"), query.from()));
			}
			if (query.to() != null) {
				predicates.add(cb.lessThan(event.<Instant>get("occurredAt"), query.to()));
			}
			if (query.flagged() != null) {
				predicates.add(cb.equal(root.get("flagged"), query.flagged()));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	/**
	 * Outcome of ingestion.
	 *
	 * @param evaluation the stored evaluation
	 * @param created {@code true} if this call evaluated and stored it; {@code false} if it
	 *                already existed (idempotent replay)
	 */
	public record Result(FraudEvaluation evaluation, boolean created) {
	}
}
