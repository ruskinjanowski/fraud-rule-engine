package com.capitech.fraud.rule;

import com.capitech.fraud.domain.TransactionEvent;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Fires when a recent prior event matches on amount and merchant but carries a different
 * {@code transactionId} — double-submission or a replayed captured payment. A redelivered
 * <em>event</em> (same {@code eventId}) never reaches this rule: idempotency absorbs it
 * before evaluation (ADR-0002, ADR-0003).
 */
@Component
public class DuplicateTransactionRule implements Rule {

	static final String CODE = "DUPLICATE_TRANSACTION";
	static final String VERSION = "1";

	private final RuleProperties.DuplicateTransaction config;

	public DuplicateTransactionRule(RuleProperties properties) {
		this.config = properties.duplicateTransaction();
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
		Optional<TransactionEvent> match = history.within(config.window()).stream()
				.filter(e -> e.getAmount().compareTo(event.getAmount()) == 0)
				.filter(e -> Objects.equals(e.getMerchant(), event.getMerchant()))
				.filter(e -> !e.getTransactionId().equals(event.getTransactionId()))
				.findFirst();
		long minutes = config.window().toMinutes();
		return match
				.map(duplicate -> RuleOutcome.hit(config.score(),
						"matches transaction %s (same amount and merchant) within %dmin"
								.formatted(duplicate.getTransactionId(), minutes)))
				.orElseGet(() -> RuleOutcome.miss(
						"no same-amount, same-merchant transaction within %dmin".formatted(minutes)));
	}
}
