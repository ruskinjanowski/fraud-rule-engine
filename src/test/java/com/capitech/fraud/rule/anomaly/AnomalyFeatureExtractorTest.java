package com.capitech.fraud.rule.anomaly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.capitech.fraud.domain.TransactionCategory;
import com.capitech.fraud.domain.TransactionEvent;
import com.capitech.fraud.rule.CustomerHistory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnomalyFeatureExtractorTest {

	private static final Instant ANCHOR = Instant.parse("2026-06-11T10:00:00Z");

	private final AnomalyFeatureExtractor extractor = new AnomalyFeatureExtractor(AnomalyTestConfig.properties());

	@Test
	void producesAFixedLengthVector() {
		double[] features = extractor.extract(event("200", ANCHOR), CustomerHistory.none(ANCHOR));

		assertThat(features).hasSize(AnomalyFeatureExtractor.DIMENSION);
	}

	@Test
	void encodesMagnitudeAndCyclicTimeOfDay() {
		double[] features = extractor.extract(event("200", ANCHOR), CustomerHistory.none(ANCHOR));

		assertThat(features[0]).isCloseTo(Math.log1p(200), within(1e-9));
		// sin^2 + cos^2 == 1 confirms the two time features are a unit-circle encoding.
		assertThat(features[1] * features[1] + features[2] * features[2]).isCloseTo(1.0, within(1e-9));
	}

	@Test
	void countsRecentEventsWithinTheVelocityWindow() {
		// Two events inside 24h, one well outside it (5 days back).
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				event("100", ANCHOR.minus(Duration.ofHours(2))),
				event("100", ANCHOR.minus(Duration.ofHours(20))),
				event("100", ANCHOR.minus(Duration.ofDays(5)))));

		double[] features = extractor.extract(event("200", ANCHOR), history);

		assertThat(features[3]).isEqualTo(2.0);
	}

	@Test
	void ratioComparesAmountToTheCustomerBaselineMean() {
		// Baseline mean over the window is 100; a 500 transaction is 5x that.
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				event("80", ANCHOR.minus(Duration.ofDays(1))),
				event("120", ANCHOR.minus(Duration.ofDays(2)))));

		double[] features = extractor.extract(event("500", ANCHOR), history);

		assertThat(features[4]).isCloseTo(5.0, within(1e-9));
	}

	@Test
	void ratioIsOneWhenThereIsNoBaselineYet() {
		double[] features = extractor.extract(event("999", ANCHOR), CustomerHistory.none(ANCHOR));

		assertThat(features[4]).isEqualTo(1.0);
	}

	private static TransactionEvent event(String amount, Instant occurredAt) {
		return new TransactionEvent(UUID.randomUUID(), "txn", "cust-1", new BigDecimal(amount),
				"ZAR", TransactionCategory.POS, "ACME", "ZA", occurredAt);
	}
}
