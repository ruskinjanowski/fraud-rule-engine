package com.capitech.fraud.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.rule.RuleProperties.AmountThreshold;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AmountThresholdRuleTest {

	private final AmountThresholdRule rule = new AmountThresholdRule(new RuleProperties(50,
			new AmountThreshold(true, new BigDecimal("10000"), 50), null, null, null, null, null, null, null, null));

	@Test
	void firesWhenAmountExceedsLimit() {
		RuleOutcome outcome = evaluate("10000.01");

		assertThat(outcome.hit()).isTrue();
		assertThat(outcome.score()).isEqualTo(50);
		assertThat(outcome.detail()).contains("exceeds");
	}

	@Test
	void doesNotFireWhenAmountEqualsLimit() {
		RuleOutcome outcome = evaluate("10000");

		assertThat(outcome.hit()).isFalse();
		assertThat(outcome.score()).isZero();
		assertThat(outcome.detail()).contains("within");
	}

	@Test
	void doesNotFireWhenAmountBelowLimit() {
		RuleOutcome outcome = evaluate("9999.99");

		assertThat(outcome.hit()).isFalse();
		assertThat(outcome.score()).isZero();
	}

	private RuleOutcome evaluate(String amount) {
		return rule.evaluate(TestEvents.event().amount(amount).build(),
				CustomerHistory.none(TestEvents.ANCHOR));
	}
}
