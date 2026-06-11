package com.capitech.fraud.rule;

import static com.capitech.fraud.rule.TestEvents.ANCHOR;
import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.domain.TransactionCategory;
import com.capitech.fraud.domain.TransactionEvent;
import com.capitech.fraud.rule.RuleProperties.ImpossibleTravel;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImpossibleTravelRuleTest {

	private final ImpossibleTravelRule rule = new ImpossibleTravelRule(new RuleProperties(50,
			null, null, null, null, null, null, new ImpossibleTravel(true, Duration.ofHours(4), 40), null, null));

	@Test
	void firesOnCardPresentCountryChangeWithinMinimumGap() {
		TransactionEvent event = TestEvents.event().category(TransactionCategory.POS).country("GB").build();
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().country("ZA").minutesBeforeAnchor(30).build()));

		RuleOutcome outcome = rule.evaluate(event, history);

		assertThat(outcome.hit()).isTrue();
		assertThat(outcome.score()).isEqualTo(40);
		assertThat(outcome.detail()).contains("GB").contains("ZA").contains("30min");
	}

	@Test
	void doesNotFireForOnlineTransactions() {
		TransactionEvent event = TestEvents.event().category(TransactionCategory.ONLINE).country("GB").build();
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().country("ZA").minutesBeforeAnchor(30).build()));

		RuleOutcome outcome = rule.evaluate(event, history);

		assertThat(outcome.hit()).isFalse();
		assertThat(outcome.detail()).contains("not card-present");
	}

	@Test
	void doesNotFireForTheSameCountry() {
		TransactionEvent event = TestEvents.event().category(TransactionCategory.ATM).country("ZA").build();
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().country("ZA").minutesBeforeAnchor(30).build()));

		assertThat(rule.evaluate(event, history).hit()).isFalse();
	}

	@Test
	void doesNotFireWhenThePreviousLocatedEventIsOutsideTheGap() {
		TransactionEvent event = TestEvents.event().category(TransactionCategory.POS).country("GB").build();
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().country("ZA").minutesBeforeAnchor(300).build()));

		assertThat(rule.evaluate(event, history).hit()).isFalse();
	}

	@Test
	void comparesAgainstTheMostRecentLocatedEvent() {
		TransactionEvent event = TestEvents.event().category(TransactionCategory.POS).country("GB").build();
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(
				TestEvents.event().country(null).minutesBeforeAnchor(10).build(),
				TestEvents.event().country("GB").minutesBeforeAnchor(20).build(),
				TestEvents.event().country("ZA").minutesBeforeAnchor(40).build()));

		// Most recent located prior is GB (20min ago) — same country, so no fire.
		assertThat(rule.evaluate(event, history).hit()).isFalse();
	}
}
