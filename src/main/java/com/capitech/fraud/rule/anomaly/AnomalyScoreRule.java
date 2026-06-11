package com.capitech.fraud.rule.anomaly;

import com.capitech.fraud.domain.TransactionEvent;
import com.capitech.fraud.rule.CustomerHistory;
import com.capitech.fraud.rule.Rule;
import com.capitech.fraud.rule.RuleOutcome;
import com.capitech.fraud.rule.RuleProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * An advisory (shadow) fraud rule backed by an unsupervised Isolation Forest (ADR-0006).
 * Where the other rules encode known fraud patterns as fixed thresholds, this one learns the
 * shape of normal transactions and fires on statistical outliers — catching unusual
 * combinations no single threshold describes.
 *
 * <p>It is {@link #advisory() advisory}: its score is recorded on the {@code rule_result}
 * audit trail but excluded from the flag total, so the ML model is observed in production
 * (how often would it fire? what does it overlap with?) without ever blocking a customer on
 * its own. Promoting it to a scoring rule is then a one-line config change, not a redesign.
 */
@Component
public class AnomalyScoreRule implements Rule {

	static final String CODE = "ANOMALY_SCORE";
	static final String VERSION = "1";

	private final AnomalyDetectionModel model;
	private final AnomalyFeatureExtractor extractor;
	private final RuleProperties.Anomaly config;

	public AnomalyScoreRule(AnomalyDetectionModel model, AnomalyFeatureExtractor extractor,
			RuleProperties properties) {
		this.model = model;
		this.extractor = extractor;
		this.config = properties.anomaly();
	}

	@Override
	public String code() {
		return CODE;
	}

	@Override
	public String version() {
		return VERSION;
	}

	@Override
	public boolean enabled() {
		return config.enabled();
	}

	@Override
	public boolean advisory() {
		return true;
	}

	@Override
	public Duration historyWindow() {
		return config.baselineWindow();
	}

	@Override
	public RuleOutcome evaluate(TransactionEvent event, CustomerHistory history) {
		double score = model.score(extractor.extract(event, history));
		if (score > config.threshold()) {
			return RuleOutcome.hit(config.score(),
					"anomaly score %.3f exceeds threshold %.3f (advisory)".formatted(score, config.threshold()));
		}
		return RuleOutcome.miss(
				"anomaly score %.3f within threshold %.3f".formatted(score, config.threshold()));
	}
}
