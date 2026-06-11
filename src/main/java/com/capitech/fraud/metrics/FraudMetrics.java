package com.capitech.fraud.metrics;

import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.domain.RuleResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Domain meters for the evaluation pipeline. Instrumented at the service seam, so every
 * evaluation is counted once in one place (ADR-0002) regardless of entry point, plus the
 * dead-letter path from the Kafka error handler.
 *
 * <p>Counters are looked up per call: Micrometer caches meters by name+tags, so repeated
 * lookups return the same instance. This keeps tag values (rule code, outcome) at the call
 * site rather than pre-registering every combination.
 */
@Component
public class FraudMetrics {

	private final MeterRegistry registry;
	private final Timer evaluationTimer;

	public FraudMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.evaluationTimer = Timer.builder("fraud.evaluation.duration")
				.description("Time to ingest and evaluate one transaction event")
				.publishPercentileHistogram()
				.register(registry);
	}

	/** Starts a timer sample for one evaluation; stop it with {@link #recordEvaluation}. */
	public Timer.Sample startEvaluation() {
		return Timer.start(registry);
	}

	/**
	 * Records one freshly produced evaluation: stops the timer, counts the outcome
	 * (flagged/clear), and increments a per-rule hit counter for every rule that fired.
	 * Idempotent replays don't call this — they neither re-evaluate nor change the totals.
	 */
	public void recordEvaluation(Timer.Sample sample, FraudEvaluation evaluation) {
		sample.stop(evaluationTimer);
		registry.counter("fraud.evaluations", "outcome", evaluation.isFlagged() ? "flagged" : "clear")
				.increment();
		for (RuleResult result : evaluation.getRuleResults()) {
			if (result.isHit()) {
				registry.counter("fraud.rule.hits", "rule", result.getRuleCode()).increment();
			}
		}
	}

	/** Counts a record routed to the dead-letter topic (Kafka error handler, ADR-0004). */
	public void recordDeadLetter() {
		registry.counter("fraud.dlt.messages").increment();
	}
}
