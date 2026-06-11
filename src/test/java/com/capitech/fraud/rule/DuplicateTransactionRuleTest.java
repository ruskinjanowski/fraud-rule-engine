package com.capitech.fraud.rule;

import static com.capitech.fraud.rule.TestEvents.ANCHOR;
import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.domain.TransactionEvent;
import com.capitech.fraud.rule.RuleProperties.DuplicateTransaction;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DuplicateTransactionRuleTest {

	private final DuplicateTransactionRule rule = new DuplicateTransactionRule(new RuleProperties(50,
			null, null, null, null, new DuplicateTransaction(true, Duration.ofMinutes(5), 40),
			null, null, null, null));

	private final TransactionEvent event = TestEvents.event()
			.transactionId("txn-current").amount("250").merchant("Takealot").build();

	@Test
	void firesOnSameAmountAndMerchantWithDifferentTransactionId() {
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(TestEvents.event()
				.transactionId("txn-other").amount("250").merchant("Takealot").minutesBeforeAnchor(2).build()));

		RuleOutcome outcome = rule.evaluate(event, history);

		assertThat(outcome.hit()).isTrue();
		assertThat(outcome.score()).isEqualTo(40);
		assertThat(outcome.detail()).contains("txn-other");
	}

	@Test
	void doesNotFireForTheSameTransactionId() {
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(TestEvents.event()
				.transactionId("txn-current").amount("250").merchant("Takealot").minutesBeforeAnchor(2).build()));

		assertThat(rule.evaluate(event, history).hit()).isFalse();
	}

	@Test
	void doesNotFireWhenAmountOrMerchantDiffer() {
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().transactionId("txn-a").amount("251").merchant("Takealot")
						.minutesBeforeAnchor(2).build(),
				TestEvents.event().transactionId("txn-b").amount("250").merchant("Checkers")
						.minutesBeforeAnchor(2).build()));

		assertThat(rule.evaluate(event, history).hit()).isFalse();
	}

	@Test
	void doesNotFireOutsideTheWindow() {
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(TestEvents.event()
				.transactionId("txn-other").amount("250").merchant("Takealot").minutesBeforeAnchor(6).build()));

		assertThat(rule.evaluate(event, history).hit()).isFalse();
	}
}
