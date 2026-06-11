package com.capitech.fraud.rule;

import com.capitech.fraud.domain.TransactionCategory;
import com.capitech.fraud.domain.TransactionEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Builder for transaction events in rule unit tests, anchored at a fixed instant. */
final class TestEvents {

	/** 12:00 SAST — comfortably outside the default night window. */
	static final Instant ANCHOR = Instant.parse("2026-06-11T10:00:00Z");

	private TestEvents() {
	}

	static Builder event() {
		return new Builder();
	}

	static class Builder {

		private String transactionId = "txn-" + UUID.randomUUID();
		private String amount = "100";
		private TransactionCategory category = TransactionCategory.ONLINE;
		private String merchant = "ACME";
		private String country = "ZA";
		private Instant occurredAt = ANCHOR;

		Builder transactionId(String transactionId) {
			this.transactionId = transactionId;
			return this;
		}

		Builder amount(String amount) {
			this.amount = amount;
			return this;
		}

		Builder category(TransactionCategory category) {
			this.category = category;
			return this;
		}

		Builder merchant(String merchant) {
			this.merchant = merchant;
			return this;
		}

		Builder country(String country) {
			this.country = country;
			return this;
		}

		Builder occurredAt(Instant occurredAt) {
			this.occurredAt = occurredAt;
			return this;
		}

		Builder minutesBeforeAnchor(long minutes) {
			this.occurredAt = ANCHOR.minus(Duration.ofMinutes(minutes));
			return this;
		}

		TransactionEvent build() {
			return new TransactionEvent(UUID.randomUUID(), transactionId, "cust-1", new BigDecimal(amount),
					"ZAR", category, merchant, country, occurredAt);
		}
	}
}
