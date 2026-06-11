package com.capitech.fraud.rule;

import com.capitech.fraud.domain.TransactionEvent;
import java.time.Duration;

/**
 * A single fraud rule. Implementations are Spring beans, discovered and run by the
 * {@link RuleEngine} against every transaction event (ADR-0002).
 *
 * <p>Adding a rule to the engine is adding an implementation to the classpath — there is
 * no rule-management table at this stage. {@link #code()} and {@link #version()} are
 * persisted with each result so the audit trail records exactly which rule, at which
 * version, produced an outcome.
 *
 * <p>Rules are pure functions of the event and its {@link CustomerHistory} (ADR-0003):
 * they do no I/O of their own. A stateful rule declares how far back it looks via
 * {@link #historyWindow()} and reads prior events from the supplied history.
 */
public interface Rule {

	/** Stable identifier for this rule, persisted on every {@code rule_result} (e.g. {@code AMOUNT_THRESHOLD}). */
	String code();

	/** Version of this rule's logic/parameters, persisted so historical decisions stay interpretable after edits. */
	String version();

	/**
	 * Whether this rule participates in evaluations. Disabled rules are skipped entirely
	 * and leave no {@code rule_result} — turning a rule off is config, not a deploy.
	 */
	default boolean enabled() {
		return true;
	}

	/**
	 * Whether this rule is <em>advisory</em> (shadow mode): it is evaluated and its
	 * {@code rule_result} is persisted for the audit trail, but its score is excluded from
	 * the evaluation's total and so can never change the {@code flagged} outcome (ADR-0006).
	 *
	 * <p>The seam for trialling a rule in production — measuring how often it would fire and
	 * what it overlaps with — before it is ever allowed to affect a customer's decision. The
	 * default ({@code false}) is a normal scoring rule that contributes to the flag.
	 */
	default boolean advisory() {
		return false;
	}

	/**
	 * How far back this rule reads {@link CustomerHistory}. {@link Duration#ZERO} (the
	 * default) means stateless — the event alone answers the rule. The engine loads one
	 * history covering the largest window across enabled rules.
	 */
	default Duration historyWindow() {
		return Duration.ZERO;
	}

	/** Applies the rule to one event. Must not mutate the event; returns an outcome whether or not it fired. */
	RuleOutcome evaluate(TransactionEvent event, CustomerHistory history);
}
