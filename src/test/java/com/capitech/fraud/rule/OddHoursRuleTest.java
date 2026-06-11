package com.capitech.fraud.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.rule.RuleProperties.OddHours;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OddHoursRuleTest {

	private final OddHoursRule rule = ruleWith("00:00", "04:30");

	@Test
	void firesInsideTheNightWindow() {
		// 00:10 UTC = 02:10 SAST
		RuleOutcome outcome = rule.evaluate(eventAt("2026-06-11T00:10:00Z"), none());

		assertThat(outcome.hit()).isTrue();
		assertThat(outcome.score()).isEqualTo(20);
		assertThat(outcome.detail()).contains("02:10");
	}

	@Test
	void startIsInclusive() {
		// 22:00 UTC = 00:00 SAST
		assertThat(rule.evaluate(eventAt("2026-06-10T22:00:00Z"), none()).hit()).isTrue();
	}

	@Test
	void endIsExclusive() {
		// 02:30 UTC = 04:30 SAST
		assertThat(rule.evaluate(eventAt("2026-06-11T02:30:00Z"), none()).hit()).isFalse();
	}

	@Test
	void doesNotFireDuringTheDay() {
		// 10:00 UTC = 12:00 SAST
		assertThat(rule.evaluate(eventAt("2026-06-11T10:00:00Z"), none()).hit()).isFalse();
	}

	@Test
	void supportsWindowsWrappingMidnight() {
		OddHoursRule wrapping = ruleWith("22:00", "06:00");

		// 21:00 UTC = 23:00 SAST and 02:00 UTC = 04:00 SAST are both night; 10:00 UTC is not.
		assertThat(wrapping.evaluate(eventAt("2026-06-10T21:00:00Z"), none()).hit()).isTrue();
		assertThat(wrapping.evaluate(eventAt("2026-06-11T02:00:00Z"), none()).hit()).isTrue();
		assertThat(wrapping.evaluate(eventAt("2026-06-11T10:00:00Z"), none()).hit()).isFalse();
	}

	private static OddHoursRule ruleWith(String nightStart, String nightEnd) {
		return new OddHoursRule(new RuleProperties(50, null, null, null, null, null,
				new OddHours(true, "Africa/Johannesburg", nightStart, nightEnd, 20), null, null, null));
	}

	private static com.capitech.fraud.domain.TransactionEvent eventAt(String occurredAt) {
		return TestEvents.event().occurredAt(Instant.parse(occurredAt)).build();
	}

	private static CustomerHistory none() {
		return CustomerHistory.none(TestEvents.ANCHOR);
	}
}
