package com.capitech.fraud.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.domain.RuleResult;
import com.capitech.fraud.domain.TransactionCategory;
import com.capitech.fraud.domain.TransactionEvent;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The domain meter taxonomy, exercised against an in-memory registry: outcome
 * counter, per-rule hit counter (only fired rules), evaluation timer, and the dead-letter
 * counter.
 */
class FraudMetricsTest {

	private SimpleMeterRegistry registry;
	private FraudMetrics metrics;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		metrics = new FraudMetrics(registry);
	}

	@Test
	void recordsOutcomeRuleHitsAndTiming() {
		FraudEvaluation evaluation = flaggedEvaluation();

		Timer.Sample sample = metrics.startEvaluation();
		metrics.recordEvaluation(sample, evaluation);

		assertThat(registry.counter("fraud.evaluations", "outcome", "flagged").count()).isEqualTo(1.0);
		assertThat(registry.counter("fraud.evaluations", "outcome", "clear").count()).isZero();
		// Only the rule that fired is counted; the non-hit rule leaves no hit meter.
		assertThat(registry.counter("fraud.rule.hits", "rule", "AMOUNT_THRESHOLD").count()).isEqualTo(1.0);
		assertThat(registry.find("fraud.rule.hits").tag("rule", "VELOCITY").counter()).isNull();
		assertThat(registry.timer("fraud.evaluation.duration").count()).isEqualTo(1L);
	}

	@Test
	void clearEvaluationCountsAsClear() {
		FraudEvaluation evaluation = new FraudEvaluation(event(), false, 0);

		metrics.recordEvaluation(metrics.startEvaluation(), evaluation);

		assertThat(registry.counter("fraud.evaluations", "outcome", "clear").count()).isEqualTo(1.0);
		assertThat(registry.counter("fraud.evaluations", "outcome", "flagged").count()).isZero();
	}

	@Test
	void countsDeadLetters() {
		metrics.recordDeadLetter();
		metrics.recordDeadLetter();

		assertThat(registry.counter("fraud.dlt.messages").count()).isEqualTo(2.0);
	}

	private static FraudEvaluation flaggedEvaluation() {
		FraudEvaluation evaluation = new FraudEvaluation(event(), true, 50);
		evaluation.addRuleResult(new RuleResult("AMOUNT_THRESHOLD", "v1", true, 50, "over limit"));
		evaluation.addRuleResult(new RuleResult("VELOCITY", "v1", false, 0, null));
		return evaluation;
	}

	private static TransactionEvent event() {
		return new TransactionEvent(UUID.randomUUID(), "txn-1", "cust-1", new BigDecimal("25000.00"),
				"ZAR", TransactionCategory.ONLINE, "ACME", "ZA", Instant.parse("2026-06-11T10:00:00Z"));
	}
}
