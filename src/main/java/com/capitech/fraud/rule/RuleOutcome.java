package com.capitech.fraud.rule;

/**
 * The result of applying a single {@link Rule} to a transaction: whether it fired,
 * how much it contributes to the fraud score, and a human-readable reason.
 *
 * <p>A rule always returns an outcome — a {@code hit = false} outcome (score 0) is still
 * recorded, so the evaluation keeps a complete "why didn't this fire" audit trail (ADR-0001).
 */
public record RuleOutcome(boolean hit, int score, String detail) {

	/** Convenience for the common "did not fire" case. */
	public static RuleOutcome miss(String detail) {
		return new RuleOutcome(false, 0, detail);
	}

	/** Convenience for a rule that fired and contributes {@code score} to the total. */
	public static RuleOutcome hit(int score, String detail) {
		return new RuleOutcome(true, score, detail);
	}
}
