package com.capitech.fraud.rule;

import static com.capitech.fraud.rule.TestEvents.ANCHOR;
import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.rule.RuleProperties.CardTesting;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CardTestingRuleTest {

	private final CardTestingRule rule = new CardTestingRule(new RuleProperties(50, null, null, null,
			new CardTesting(true, new BigDecimal("50"), 5, Duration.ofMinutes(10), 45),
			null, null, null, null, null));

	@Test
	void firesOnAMicroAmountBurst() {
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().amount("5").minutesBeforeAnchor(8).build(),
				TestEvents.event().amount("10").minutesBeforeAnchor(6).build(),
				TestEvents.event().amount("1").minutesBeforeAnchor(4).build(),
				TestEvents.event().amount("15").minutesBeforeAnchor(2).build()));

		RuleOutcome outcome = rule.evaluate(TestEvents.event().amount("8").build(), history);

		assertThat(outcome.hit()).isTrue();
		assertThat(outcome.score()).isEqualTo(45);
		assertThat(outcome.detail()).contains("5 micro-amounts");
	}

	@Test
	void doesNotFireWhenCurrentAmountIsNotMicro() {
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().amount("5").minutesBeforeAnchor(8).build(),
				TestEvents.event().amount("10").minutesBeforeAnchor(6).build(),
				TestEvents.event().amount("1").minutesBeforeAnchor(4).build(),
				TestEvents.event().amount("15").minutesBeforeAnchor(2).build()));

		RuleOutcome outcome = rule.evaluate(TestEvents.event().amount("900").build(), history);

		assertThat(outcome.hit()).isFalse();
		assertThat(outcome.detail()).contains("not a card-testing probe");
	}

	@Test
	void countsOnlyMicroAmountsInTheWindow() {
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().amount("5").minutesBeforeAnchor(8).build(),
				TestEvents.event().amount("900").minutesBeforeAnchor(6).build(),
				TestEvents.event().amount("10").minutesBeforeAnchor(4).build(),
				TestEvents.event().amount("2").minutesBeforeAnchor(12).build()));

		RuleOutcome outcome = rule.evaluate(TestEvents.event().amount("8").build(), history);

		assertThat(outcome.hit()).isFalse();
		assertThat(outcome.detail()).contains("3 micro-amounts");
	}
}
