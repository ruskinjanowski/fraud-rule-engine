package com.capitech.fraud.rule.anomaly;

import com.capitech.fraud.rule.RuleProperties;
import java.time.Duration;

/** Shared {@link RuleProperties} with a populated anomaly block for the anomaly unit tests. */
final class AnomalyTestConfig {

	private AnomalyTestConfig() {
	}

	static RuleProperties properties() {
		return properties(anomaly());
	}

	static RuleProperties properties(RuleProperties.Anomaly anomaly) {
		return new RuleProperties(50, null, null, null, null, null, null, null, null, anomaly);
	}

	static RuleProperties.Anomaly anomaly() {
		return new RuleProperties.Anomaly(true, 25, 0.62, "Africa/Johannesburg",
				Duration.ofDays(7), Duration.ofHours(24),
				new RuleProperties.Anomaly.Training(200, 40, 42L, 100));
	}
}
