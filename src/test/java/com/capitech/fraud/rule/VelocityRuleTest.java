package com.capitech.fraud.rule;

import static com.capitech.fraud.rule.TestEvents.ANCHOR;
import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.domain.TransactionEvent;
import com.capitech.fraud.rule.RuleProperties.Velocity;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class VelocityRuleTest {

	private final VelocityRule rule = new VelocityRule(new RuleProperties(50, null,
			new Velocity(true, 3, Duration.ofMinutes(10), 30), null, null, null, null, null, null, null));

	private final TransactionEvent event = TestEvents.event().build();

	@Test
	void firesWhenCountIncludingCurrentExceedsLimit() {
		CustomerHistory history = historyOfPriors(2, 5, 8);

		RuleOutcome outcome = rule.evaluate(event, history);

		assertThat(outcome.hit()).isTrue();
		assertThat(outcome.score()).isEqualTo(30);
		assertThat(outcome.detail()).contains("4 events");
	}

	@Test
	void doesNotFireAtTheLimit() {
		CustomerHistory history = historyOfPriors(2, 5);

		RuleOutcome outcome = rule.evaluate(event, history);

		assertThat(outcome.hit()).isFalse();
		assertThat(outcome.score()).isZero();
	}

	@Test
	void ignoresEventsOutsideTheWindow() {
		CustomerHistory history = historyOfPriors(11, 30, 45);

		RuleOutcome outcome = rule.evaluate(event, history);

		assertThat(outcome.hit()).isFalse();
	}

	private static CustomerHistory historyOfPriors(long... minutesBefore) {
		List<TransactionEvent> priors = java.util.Arrays.stream(minutesBefore)
				.mapToObj(m -> TestEvents.event().minutesBeforeAnchor(m).build())
				.toList();
		return new CustomerHistory(ANCHOR, priors);
	}
}
