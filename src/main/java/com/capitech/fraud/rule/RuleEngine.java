package com.capitech.fraud.rule;

import com.capitech.fraud.domain.FraudEvaluation;
import com.capitech.fraud.domain.RuleResult;
import com.capitech.fraud.domain.TransactionEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Runs the enabled {@link Rule} beans against a transaction event and assembles the
 * resulting {@link FraudEvaluation} aggregate (ADR-0002, ADR-0003).
 *
 * <p>Every enabled rule contributes one {@link RuleResult} — fired or not — for a complete
 * audit trail; disabled rules leave no result. The summed score decides the flag:
 * {@code flagged = totalScore >= flagThreshold}. {@link Rule#advisory() Advisory} (shadow)
 * rules are the exception: their result is recorded but their score is excluded from the
 * total, so they are observed without affecting the outcome (ADR-0006). The engine is pure
 * (no persistence and no I/O); the caller loads the {@link CustomerHistory} and owns the
 * transaction.
 */
@Component
public class RuleEngine {

	private final List<Rule> rules;
	private final int flagThreshold;

	public RuleEngine(List<Rule> rules, RuleProperties properties) {
		this.rules = rules.stream().filter(Rule::enabled).toList();
		this.flagThreshold = properties.flagThreshold();
	}

	/**
	 * The largest {@link Rule#historyWindow()} across enabled rules — how much history the
	 * caller must load for one evaluation. {@link Duration#ZERO} when every rule is stateless.
	 */
	public Duration requiredLookback() {
		return rules.stream()
				.map(Rule::historyWindow)
				.max(Comparator.naturalOrder())
				.orElse(Duration.ZERO);
	}

	/**
	 * Applies every enabled rule to {@code event} and returns the assembled evaluation,
	 * with one {@link RuleResult} per rule attached. Not yet persisted — the caller saves it.
	 */
	public FraudEvaluation evaluate(TransactionEvent event, CustomerHistory history) {
		int totalScore = 0;
		List<RuleResult> results = new ArrayList<>(rules.size());
		for (Rule rule : rules) {
			RuleOutcome outcome = rule.evaluate(event, history);
			// Advisory (shadow) rules are recorded but never counted toward the flag (ADR-0006).
			if (!rule.advisory()) {
				totalScore += outcome.score();
			}
			results.add(new RuleResult(rule.code(), rule.version(),
					outcome.hit(), outcome.score(), outcome.detail()));
		}

		boolean flagged = totalScore >= flagThreshold;
		FraudEvaluation evaluation = new FraudEvaluation(event, flagged, totalScore);
		results.forEach(evaluation::addRuleResult);
		return evaluation;
	}
}
