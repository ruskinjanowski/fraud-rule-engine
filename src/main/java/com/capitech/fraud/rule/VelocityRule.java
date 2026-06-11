package com.capitech.fraud.rule;

import com.capitech.fraud.domain.TransactionEvent;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Fires when the customer's event count in a sliding window — the evaluated event
 * included — exceeds a limit. The canonical fraud rule: card-testing bursts and
 * account-takeover drains both produce a transaction frequency no human shopping
 * pattern resembles, regardless of amounts (ADR-0003).
 */
@Component
public class VelocityRule implements Rule {

	static final String CODE = "VELOCITY";
	static final String VERSION = "1";

	private final RuleProperties.Velocity config;

	public VelocityRule(RuleProperties properties) {
		this.config = properties.velocity();
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
	public Duration historyWindow() {
		return config.window();
	}

	@Override
	public RuleOutcome evaluate(TransactionEvent event, CustomerHistory history) {
		int count = history.within(config.window()).size() + 1;
		long minutes = config.window().toMinutes();
		if (count > config.maxEvents()) {
			return RuleOutcome.hit(config.score(),
					"%d events in %dmin exceeds limit %d".formatted(count, minutes, config.maxEvents()));
		}
		return RuleOutcome.miss(
				"%d events in %dmin within limit %d".formatted(count, minutes, config.maxEvents()));
	}
}
