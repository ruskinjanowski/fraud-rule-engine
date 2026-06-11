package com.capitech.fraud.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.rule.RuleProperties.HighRiskCountry;
import java.util.List;
import org.junit.jupiter.api.Test;

class HighRiskCountryRuleTest {

	@Test
	void firesWhenCountryIsOnTheListCaseInsensitively() {
		HighRiskCountryRule rule = ruleWith(List.of(" xx", "YY"));

		RuleOutcome outcome = rule.evaluate(TestEvents.event().country("XX").build(), none());

		assertThat(outcome.hit()).isTrue();
		assertThat(outcome.score()).isEqualTo(15);
	}

	@Test
	void doesNotFireForCountriesOffTheList() {
		HighRiskCountryRule rule = ruleWith(List.of("XX"));

		assertThat(rule.evaluate(TestEvents.event().country("ZA").build(), none()).hit()).isFalse();
	}

	@Test
	void doesNotFireWithoutACountry() {
		HighRiskCountryRule rule = ruleWith(List.of("XX"));

		assertThat(rule.evaluate(TestEvents.event().country(null).build(), none()).hit()).isFalse();
	}

	@Test
	void doesNotFireWhenTheListIsUnset() {
		HighRiskCountryRule rule = ruleWith(null);

		assertThat(rule.evaluate(TestEvents.event().country("ZA").build(), none()).hit()).isFalse();
	}

	private static HighRiskCountryRule ruleWith(List<String> countries) {
		return new HighRiskCountryRule(new RuleProperties(50, null, null, null, null, null, null, null,
				new HighRiskCountry(true, countries, 15), null));
	}

	private static CustomerHistory none() {
		return CustomerHistory.none(TestEvents.ANCHOR);
	}
}
