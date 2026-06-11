package com.capitech.fraud.rule;

import com.capitech.fraud.domain.TransactionEvent;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Fires when a transaction's amount exceeds a configured limit — the simplest
 * deterministic fraud signal: fraudsters monetizing a compromised account extract
 * maximum value before the card is killed (ADR-0002).
 *
 * <p>Known limitation: the comparison is currency-naive — the limit is a single number
 * applied regardless of {@code currency}. The per-customer-baseline variant
 * ({@code AMOUNT_DEVIATION}, planned) is the production refinement of this rule.
 */
@Component
public class AmountThresholdRule implements Rule {

	static final String CODE = "AMOUNT_THRESHOLD";
	static final String VERSION = "1";

	private final boolean enabled;
	private final BigDecimal limit;
	private final int score;

	public AmountThresholdRule(RuleProperties properties) {
		this.enabled = properties.amountThreshold().enabled();
		this.limit = properties.amountThreshold().limit();
		this.score = properties.amountThreshold().score();
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
		return enabled;
	}

	@Override
	public RuleOutcome evaluate(TransactionEvent event, CustomerHistory history) {
		BigDecimal amount = event.getAmount();
		if (amount.compareTo(limit) > 0) {
			return RuleOutcome.hit(score,
					"amount %s exceeds limit %s".formatted(amount.toPlainString(), limit.toPlainString()));
		}
		return RuleOutcome.miss(
				"amount %s within limit %s".formatted(amount.toPlainString(), limit.toPlainString()));
	}
}
