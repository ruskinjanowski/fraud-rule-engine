package com.capitech.fraud.rule;

import com.capitech.fraud.domain.TransactionEvent;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Fires when the evaluated event is itself a micro-amount and the customer's micro-amount
 * count in the window reaches a minimum. Fraudsters validate stolen card details with
 * rapid bursts of tiny payments before a real spend — clusters of near-identical micro
 * attempts are the fingerprint of automated testing tools (ADR-0003).
 */
@Component
public class CardTestingRule implements Rule {

	static final String CODE = "CARD_TESTING";
	static final String VERSION = "1";

	private final RuleProperties.CardTesting config;

	public CardTestingRule(RuleProperties properties) {
		this.config = properties.cardTesting();
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
		if (event.getAmount().compareTo(config.microAmount()) > 0) {
			return RuleOutcome.miss("amount %s above micro-amount %s — not a card-testing probe"
					.formatted(event.getAmount().toPlainString(), config.microAmount().toPlainString()));
		}
		long count = history.within(config.window()).stream()
				.filter(e -> e.getAmount().compareTo(config.microAmount()) <= 0)
				.count() + 1;
		long minutes = config.window().toMinutes();
		if (count >= config.minCount()) {
			return RuleOutcome.hit(config.score(), "%d micro-amounts (<= %s) in %dmin reaches limit %d"
					.formatted(count, config.microAmount().toPlainString(), minutes, config.minCount()));
		}
		return RuleOutcome.miss("%d micro-amounts (<= %s) in %dmin below limit %d"
				.formatted(count, config.microAmount().toPlainString(), minutes, config.minCount()));
	}
}
