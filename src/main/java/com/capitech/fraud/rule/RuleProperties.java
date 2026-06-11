package com.capitech.fraud.rule;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized, env-overridable tuning for the rule engine (ADR-0002, ADR-0003).
 * Bound from {@code fraud.rules.*}; see {@code application.yaml} for defaults.
 *
 * <p>Every rule carries an {@code enabled} flag and a {@code score} (its contribution to
 * the fraud score when it fires), so switching a rule off or re-weighting it is
 * operations, not a deploy.
 *
 * @param flagThreshold an evaluation is flagged when the summed rule score reaches this value
 * @param amountThreshold parameters for {@link AmountThresholdRule}
 * @param velocity parameters for {@link VelocityRule}
 * @param cumulativeAmount parameters for {@link CumulativeAmountRule}
 * @param cardTesting parameters for {@link CardTestingRule}
 * @param duplicateTransaction parameters for {@link DuplicateTransactionRule}
 * @param oddHours parameters for {@link OddHoursRule}
 * @param impossibleTravel parameters for {@link ImpossibleTravelRule}
 * @param highRiskCountry parameters for {@link HighRiskCountryRule}
 * @param anomaly parameters for the ML anomaly scorer (ADR-0006)
 */
@ConfigurationProperties(prefix = "fraud.rules")
public record RuleProperties(int flagThreshold, AmountThreshold amountThreshold, Velocity velocity,
		CumulativeAmount cumulativeAmount, CardTesting cardTesting, DuplicateTransaction duplicateTransaction,
		OddHours oddHours, ImpossibleTravel impossibleTravel, HighRiskCountry highRiskCountry, Anomaly anomaly) {

	/**
	 * @param limit transactions with an amount strictly greater than this fire the rule
	 */
	public record AmountThreshold(boolean enabled, BigDecimal limit, int score) {
	}

	/**
	 * @param maxEvents fires when the customer's event count in the window (including the
	 *                  evaluated event) strictly exceeds this
	 * @param window sliding window, counted back from the event's {@code occurredAt}
	 */
	public record Velocity(boolean enabled, int maxEvents, Duration window, int score) {
	}

	/**
	 * @param limit fires when the customer's summed amounts in the window (including the
	 *              evaluated event) strictly exceed this
	 */
	public record CumulativeAmount(boolean enabled, BigDecimal limit, Duration window, int score) {
	}

	/**
	 * @param microAmount amounts at or below this count as card-testing probes
	 * @param minCount fires when the micro-amount count in the window (including the
	 *                 evaluated event, which must itself be micro) reaches this
	 */
	public record CardTesting(boolean enabled, BigDecimal microAmount, int minCount, Duration window, int score) {
	}

	/**
	 * @param window how far back to look for a same-amount, same-merchant transaction
	 *               with a different {@code transactionId}
	 */
	public record DuplicateTransaction(boolean enabled, Duration window, int score) {
	}

	/**
	 * @param zone IANA zone the night window is defined in (e.g. {@code Africa/Johannesburg})
	 * @param nightStart inclusive start of the night window, {@code HH:mm}
	 * @param nightEnd exclusive end of the night window, {@code HH:mm}; may wrap past midnight
	 */
	public record OddHours(boolean enabled, String zone, String nightStart, String nightEnd, int score) {
	}

	/**
	 * @param minGap fires when a card-present event's country differs from the customer's
	 *               previous located event less than this long before it
	 */
	public record ImpossibleTravel(boolean enabled, Duration minGap, int score) {
	}

	/**
	 * @param countries ISO 3166-1 alpha-2 codes considered high-risk (e.g. the FATF
	 *                  high-risk jurisdiction list); empty disables the signal in practice
	 */
	public record HighRiskCountry(boolean enabled, List<String> countries, int score) {
	}

	/**
	 * The unsupervised ML anomaly scorer (ADR-0006). Always {@link Rule#advisory() advisory}:
	 * it fires when an Isolation Forest scores a transaction above {@code threshold}, and the
	 * resulting {@code score} is recorded but never counted toward the flag.
	 *
	 * @param enabled whether the rule participates (the model still trains at startup)
	 * @param score nominal contribution recorded when it fires (advisory — excluded from the total)
	 * @param threshold Isolation Forest anomaly score in {@code (0,1]} above which it fires;
	 *                  ~0.5 is normal, {@code > 0.6} is anomalous
	 * @param zone IANA zone the hour-of-day feature is computed in (e.g. {@code Africa/Johannesburg})
	 * @param baselineWindow history window for the per-customer amount baseline (also the rule's
	 *                       {@link Rule#historyWindow() lookback})
	 * @param velocityWindow shorter window for the recent-transaction-count feature
	 * @param training synthetic-data parameters used to fit the model at startup
	 */
	public record Anomaly(boolean enabled, int score, double threshold, String zone,
			Duration baselineWindow, Duration velocityWindow, Training training) {

		/**
		 * @param customers number of synthetic customers to generate
		 * @param eventsPerCustomer normal transactions generated per synthetic customer
		 * @param seed RNG seed — fixed so the model is identical on every boot (reproducible)
		 * @param trees number of isolation trees in the forest
		 */
		public record Training(int customers, int eventsPerCustomer, long seed, int trees) {
		}
	}
}
