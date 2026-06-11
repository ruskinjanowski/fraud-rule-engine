package com.capitech.fraud.rule.anomaly;

import com.capitech.fraud.domain.TransactionEvent;
import com.capitech.fraud.rule.CustomerHistory;
import com.capitech.fraud.rule.RuleProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a transaction (plus the customer's {@link CustomerHistory}) into the numeric feature
 * vector the Isolation Forest scores (ADR-0006). The <em>same</em> extractor runs over the
 * synthetic training data ({@link SyntheticTrainingData}) and over live events, so training
 * and scoring see identical features.
 *
 * <p>The vector deliberately mixes <em>intrinsic</em> features (how large, what time of day)
 * with <em>contextual</em> ones (relative to this customer's own recent behaviour), so the
 * model can catch an amount that is normal in absolute terms but abnormal for the customer —
 * something the fixed-threshold rules cannot see.
 */
@Component
public class AnomalyFeatureExtractor {

	/** Length of the produced vector; asserted by callers building training matrices. */
	public static final int DIMENSION = 5;

	private final ZoneId zone;
	private final Duration baselineWindow;
	private final Duration velocityWindow;

	public AnomalyFeatureExtractor(RuleProperties properties) {
		RuleProperties.Anomaly config = properties.anomaly();
		this.zone = ZoneId.of(config.zone());
		this.baselineWindow = config.baselineWindow();
		this.velocityWindow = config.velocityWindow();
	}

	/**
	 * Features, in order:
	 * <ol>
	 *   <li>{@code log1p(amount)} — magnitude, compressed so large amounts don't dominate;</li>
	 *   <li>{@code sin}, {@code 3. cos} of the time-of-day angle — a cyclic encoding so 23:59
	 *       and 00:01 are near each other rather than at opposite ends of a 0–24 scale;</li>
	 *   <li>recent transaction count in {@code velocityWindow} — burstiness;</li>
	 *   <li>amount ÷ the customer's mean amount over {@code baselineWindow} — how unusual this
	 *       amount is <em>for this customer</em> (1.0 when there is no baseline yet).</li>
	 * </ol>
	 */
	public double[] extract(TransactionEvent event, CustomerHistory history) {
		double amount = event.getAmount().doubleValue();

		List<TransactionEvent> baseline = history.within(baselineWindow);
		double meanAmount = baseline.stream()
				.mapToDouble(e -> e.getAmount().doubleValue())
				.average()
				.orElse(amount);
		double amountRatio = meanAmount > 0 ? amount / meanAmount : 1.0;

		int recentCount = history.within(velocityWindow).size();

		double dayAngle = 2 * Math.PI * timeOfDayFraction(event.getOccurredAt());

		return new double[] {
				Math.log1p(amount),
				Math.sin(dayAngle),
				Math.cos(dayAngle),
				recentCount,
				amountRatio
		};
	}

	/** Seconds-into-the-day, in the configured zone, as a fraction of 24h in {@code [0,1)}. */
	private double timeOfDayFraction(Instant instant) {
		return LocalTime.ofInstant(instant, zone).toSecondOfDay() / 86_400.0;
	}
}
