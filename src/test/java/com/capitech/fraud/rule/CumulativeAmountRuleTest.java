package com.capitech.fraud.rule;

import static com.capitech.fraud.rule.TestEvents.ANCHOR;
import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.rule.RuleProperties.CumulativeAmount;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CumulativeAmountRuleTest {

	private final CumulativeAmountRule rule = new CumulativeAmountRule(new RuleProperties(50, null, null,
			new CumulativeAmount(true, new BigDecimal("50000"), Duration.ofHours(1), 35),
			null, null, null, null, null, null));

	@Test
	void firesWhenWindowedSumExceedsLimit() {
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().amount("30000").minutesBeforeAnchor(40).build(),
				TestEvents.event().amount("15000").minutesBeforeAnchor(20).build()));

		RuleOutcome outcome = rule.evaluate(TestEvents.event().amount("6000").build(), history);

		assertThat(outcome.hit()).isTrue();
		assertThat(outcome.score()).isEqualTo(35);
		assertThat(outcome.detail()).contains("51000");
	}

	@Test
	void doesNotFireWhenSumEqualsLimit() {
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().amount("44000").minutesBeforeAnchor(30).build()));

		RuleOutcome outcome = rule.evaluate(TestEvents.event().amount("6000").build(), history);

		assertThat(outcome.hit()).isFalse();
		assertThat(outcome.score()).isZero();
	}

	@Test
	void ignoresAmountsOutsideTheWindow() {
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().amount("49000").minutesBeforeAnchor(61).build()));

		RuleOutcome outcome = rule.evaluate(TestEvents.event().amount("6000").build(), history);

		assertThat(outcome.hit()).isFalse();
	}
}
