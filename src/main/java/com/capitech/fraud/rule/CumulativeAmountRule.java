package com.capitech.fraud.rule;

import com.capitech.fraud.domain.TransactionEvent;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Fires when the customer's summed amounts in a sliding window — the evaluated event
 * included — exceed a limit. The value-based twin of {@link VelocityRule}: an
 * account-takeover drain split into transactions that each stay under the per-transaction
 * threshold still adds up to one (ADR-0003).
 */
@Component
public class CumulativeAmountRule implements Rule {

	static final String CODE = "CUMULATIVE_AMOUNT";
	static final String VERSION = "1";

	private final RuleProperties.CumulativeAmount config;

	public CumulativeAmountRule(RuleProperties properties) {
		this.config = properties.cumulativeAmount();
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
		BigDecimal total = history.within(config.window()).stream()
				.map(TransactionEvent::getAmount)
				.reduce(event.getAmount(), BigDecimal::add);
		long minutes = config.window().toMinutes();
		if (total.compareTo(config.limit()) > 0) {
			return RuleOutcome.hit(config.score(), "cumulative amount %s in %dmin exceeds limit %s"
					.formatted(total.toPlainString(), minutes, config.limit().toPlainString()));
		}
		return RuleOutcome.miss("cumulative amount %s in %dmin within limit %s"
				.formatted(total.toPlainString(), minutes, config.limit().toPlainString()));
	}
}
