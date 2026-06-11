package com.capitech.fraud.rule.anomaly;

import com.capitech.fraud.domain.TransactionCategory;
import com.capitech.fraud.domain.TransactionEvent;
import com.capitech.fraud.rule.CustomerHistory;
import com.capitech.fraud.rule.RuleProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates a synthetic stream of <em>normal</em> transactions to fit the Isolation Forest
 * on (ADR-0006). Isolation Forest is unsupervised: it learns the shape of normal behaviour
 * and scores deviations — so we only need realistic "normal", not labelled fraud.
 *
 * <p><strong>Independence from the rules is deliberate.</strong> The generator draws amounts
 * and times from its own per-customer distributions, with no reference to any rule threshold
 * (the R10 000 limit, the velocity window, the night window, …). If the synthetic data were
 * shaped by the same thresholds the deterministic rules use, the model would just relearn the
 * rules and any evaluation of it would be circular. Keeping them independent means the model
 * can genuinely surprise us — flag things the fixed rules miss, and vice versa.
 *
 * <p>Each synthetic customer has a stable baseline spend and typical active hours; every
 * generated event is then run through the production {@link AnomalyFeatureExtractor} against
 * the customer's own prior events, so training rows are produced exactly as live ones are.
 * A fixed seed makes the whole matrix reproducible on every boot.
 */
final class SyntheticTrainingData {

	/** Start of the synthetic history; customers transact roughly once a day from here. */
	private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

	private SyntheticTrainingData() {
	}

	/** Builds the {@code rows × DIMENSION} feature matrix the forest is fitted on. */
	static double[][] generate(AnomalyFeatureExtractor extractor, RuleProperties.Anomaly config) {
		RuleProperties.Anomaly.Training training = config.training();
		Random rng = new Random(training.seed());
		List<double[]> rows = new ArrayList<>(training.customers() * training.eventsPerCustomer());

		for (int c = 0; c < training.customers(); c++) {
			String customerId = "synthetic-" + c;
			// Per-customer baseline spend (~R60–R1500, heavy-tailed) and a daytime activity centre.
			double baselineAmount = 200 * Math.exp(rng.nextGaussian() * 0.7);
			int activeHourUtc = 6 + rng.nextInt(12); // ~08:00–20:00 SAST after the zone shift

			List<TransactionEvent> stream = new ArrayList<>(training.eventsPerCustomer());
			for (int day = 0; stream.size() < training.eventsPerCustomer(); day++) {
				// A realistic number of purchases on this day, placed in time order so the
				// "recent count" feature sees a genuine 0–4 spread — without this, normal
				// multi-transaction days would look novel and score as false positives.
				int[] hours = sortedDaytimeHours(rng, activeHourUtc);
				for (int hour : hours) {
					if (stream.size() >= training.eventsPerCustomer()) {
						break;
					}
					double amount = Math.max(5, baselineAmount * Math.exp(rng.nextGaussian() * 0.3));
					Instant occurredAt = EPOCH
							.plus(Duration.ofDays(day))
							.plus(Duration.ofHours(hour))
							.plus(Duration.ofMinutes(rng.nextInt(60)));

					TransactionEvent event = new TransactionEvent(UUID.randomUUID(), "synthetic-txn", customerId,
							BigDecimal.valueOf(amount), "ZAR", TransactionCategory.POS, "synthetic", "ZA", occurredAt);
					rows.add(extractor.extract(event, new CustomerHistory(occurredAt, List.copyOf(stream))));
					stream.add(event);
				}
			}
		}
		return rows.toArray(double[][]::new);
	}

	/** 1–4 purchases (weighted toward 1–2), at daytime hours around the centre, sorted ascending. */
	private static int[] sortedDaytimeHours(Random rng, int activeHourUtc) {
		double r = rng.nextDouble();
		int perDay = r < 0.45 ? 1 : r < 0.75 ? 2 : r < 0.92 ? 3 : 4;
		int[] hours = new int[perDay];
		for (int j = 0; j < perDay; j++) {
			hours[j] = Math.floorMod(activeHourUtc + (int) Math.round(rng.nextGaussian() * 2), 24);
		}
		java.util.Arrays.sort(hours);
		return hours;
	}
}
