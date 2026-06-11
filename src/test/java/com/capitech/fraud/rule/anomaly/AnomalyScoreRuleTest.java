package com.capitech.fraud.rule.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.domain.TransactionCategory;
import com.capitech.fraud.domain.TransactionEvent;
import com.capitech.fraud.rule.CustomerHistory;
import com.capitech.fraud.rule.RuleOutcome;
import com.capitech.fraud.rule.RuleProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import smile.anomaly.IsolationForest;

/**
 * Exercises the rule against a real Isolation Forest fitted on the synthetic training data —
 * deterministic via the fixed seed, so the assertions are stable across runs.
 */
class AnomalyScoreRuleTest {

	private static final Instant ANCHOR = Instant.parse("2026-06-11T10:00:00Z");

	private final RuleProperties.Anomaly config = AnomalyTestConfig.anomaly();
	private final AnomalyFeatureExtractor extractor = new AnomalyFeatureExtractor(AnomalyTestConfig.properties(config));
	private final AnomalyScoreRule rule = new AnomalyScoreRule(trainedModel(), extractor,
			AnomalyTestConfig.properties(config));

	@Test
	void isAlwaysAdvisory() {
		assertThat(rule.advisory()).isTrue();
	}

	@Test
	void declaresTheBaselineWindowAsItsLookback() {
		assertThat(rule.historyWindow()).isEqualTo(config.baselineWindow());
	}

	@Test
	void doesNotFireOnATransactionTypicalForTheCustomer() {
		// Baseline ~R500 at midday; a R520 daytime purchase is unremarkable.
		CustomerHistory history = baselineHistory(500, 6);
		TransactionEvent event = event("520", ANCHOR);

		RuleOutcome outcome = rule.evaluate(event, history);

		assertThat(outcome.hit()).isFalse();
	}

	@Test
	void firesOnAnAmountWildlyOutOfPatternForTheCustomer() {
		// Same ~R500 baseline customer, now a R40 000 transaction — far outside anything
		// the model saw as normal. The amount-vs-baseline ratio isolates it immediately.
		CustomerHistory history = baselineHistory(500, 6);
		TransactionEvent event = event("40000", ANCHOR);

		RuleOutcome outcome = rule.evaluate(event, history);

		assertThat(outcome.hit()).isTrue();
		assertThat(outcome.score()).isEqualTo(config.score());
		assertThat(outcome.detail()).contains("anomaly score");
	}

	private AnomalyDetectionModel trainedModel() {
		double[][] data = SyntheticTrainingData.generate(extractor, config);
		IsolationForest forest = IsolationForest.fit(data,
				new IsolationForest.Options(config.training().trees(), 0, 0.7, 0));
		return new AnomalyDetectionModel(forest, config.threshold(), data.length);
	}

	/** A customer whose recent history is {@code count} purchases all around {@code amount}. */
	private static CustomerHistory baselineHistory(int amount, int count) {
		List<TransactionEvent> prior = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			prior.add(event(Integer.toString(amount), ANCHOR.minus(Duration.ofDays(i))));
		}
		return new CustomerHistory(ANCHOR, prior);
	}

	private static TransactionEvent event(String amount, Instant occurredAt) {
		return new TransactionEvent(UUID.randomUUID(), "txn", "cust-1", new BigDecimal(amount),
				"ZAR", TransactionCategory.POS, "ACME", "ZA", occurredAt);
	}
}
