package com.capitech.fraud.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.domain.RuleResult;
import com.capitech.fraud.domain.TransactionEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

	private final TransactionEvent event = TestEvents.event().build();
	private final CustomerHistory history = CustomerHistory.none(TestEvents.ANCHOR);

	@Test
	void recordsOneResultPerRuleIncludingMisses() {
		RuleEngine engine = engineWith(50, fixedRule("A", true, 30), fixedRule("B", false, 0));

		FraudEvaluation evaluation = engine.evaluate(event, history);

		assertThat(evaluation.getRuleResults())
				.extracting(RuleResult::getRuleCode)
				.containsExactlyInAnyOrder("A", "B");
	}

	@Test
	void sumsScoresAndFlagsWhenThresholdReached() {
		RuleEngine engine = engineWith(50, fixedRule("A", true, 30), fixedRule("B", true, 20));

		FraudEvaluation evaluation = engine.evaluate(event, history);

		assertThat(evaluation.getScore()).isEqualTo(50);
		assertThat(evaluation.isFlagged()).isTrue();
	}

	@Test
	void doesNotFlagBelowThreshold() {
		RuleEngine engine = engineWith(50, fixedRule("A", true, 30), fixedRule("B", false, 0));

		FraudEvaluation evaluation = engine.evaluate(event, history);

		assertThat(evaluation.getScore()).isEqualTo(30);
		assertThat(evaluation.isFlagged()).isFalse();
	}

	@Test
	void advisoryRuleIsRecordedButExcludedFromTheFlag() {
		// The advisory rule's score (60) alone would exceed the threshold, but shadow rules
		// never count toward the flag — its result is still recorded for the audit trail.
		RuleEngine engine = engineWith(50,
				fixedRule("REAL", false, 0),
				advisoryRule("SHADOW", true, 60));

		FraudEvaluation evaluation = engine.evaluate(event, history);

		assertThat(evaluation.getScore()).isZero();
		assertThat(evaluation.isFlagged()).isFalse();
		assertThat(evaluation.getRuleResults())
				.extracting(RuleResult::getRuleCode)
				.containsExactlyInAnyOrder("REAL", "SHADOW");
		assertThat(evaluation.getRuleResults())
				.filteredOn(r -> r.getRuleCode().equals("SHADOW"))
				.singleElement()
				.satisfies(r -> {
					assertThat(r.isHit()).isTrue();
					assertThat(r.getScore()).isEqualTo(60);
				});
	}

	@Test
	void skipsDisabledRulesEntirely() {
		RuleEngine engine = engineWith(50, fixedRule("A", true, 30),
				testRule("OFF", true, 60, false, Duration.ZERO));

		FraudEvaluation evaluation = engine.evaluate(event, history);

		assertThat(evaluation.getRuleResults())
				.extracting(RuleResult::getRuleCode)
				.containsExactly("A");
		assertThat(evaluation.isFlagged()).isFalse();
	}

	@Test
	void requiredLookbackIsTheLargestEnabledRuleWindow() {
		RuleEngine engine = engineWith(50,
				testRule("SHORT", false, 0, true, Duration.ofMinutes(5)),
				testRule("LONG", false, 0, true, Duration.ofHours(4)),
				testRule("DISABLED", false, 0, false, Duration.ofDays(30)));

		assertThat(engine.requiredLookback()).isEqualTo(Duration.ofHours(4));
	}

	@Test
	void requiredLookbackIsZeroWhenAllRulesAreStateless() {
		RuleEngine engine = engineWith(50, fixedRule("A", false, 0));

		assertThat(engine.requiredLookback()).isEqualTo(Duration.ZERO);
	}

	private RuleEngine engineWith(int flagThreshold, Rule... rules) {
		return new RuleEngine(List.of(rules),
				new RuleProperties(flagThreshold, null, null, null, null, null, null, null, null, null));
	}

	private static Rule fixedRule(String code, boolean hit, int score) {
		return testRule(code, hit, score, true, Duration.ZERO);
	}

	private static Rule advisoryRule(String code, boolean hit, int score) {
		Rule delegate = testRule(code, hit, score, true, Duration.ZERO);
		return new Rule() {
			@Override
			public String code() {
				return delegate.code();
			}

			@Override
			public String version() {
				return delegate.version();
			}

			@Override
			public boolean advisory() {
				return true;
			}

			@Override
			public RuleOutcome evaluate(TransactionEvent event, CustomerHistory history) {
				return delegate.evaluate(event, history);
			}
		};
	}

	private static Rule testRule(String code, boolean hit, int score, boolean enabled, Duration window) {
		return new Rule() {
			@Override
			public String code() {
				return code;
			}

			@Override
			public String version() {
				return "test";
			}

			@Override
			public boolean enabled() {
				return enabled;
			}

			@Override
			public Duration historyWindow() {
				return window;
			}

			@Override
			public RuleOutcome evaluate(TransactionEvent event, CustomerHistory history) {
				return new RuleOutcome(hit, score, code + " outcome");
			}
		};
	}
}
