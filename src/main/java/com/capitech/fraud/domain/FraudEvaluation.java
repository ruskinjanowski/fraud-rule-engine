package com.capitech.fraud.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of running the rule set against one {@link TransactionEvent}.
 *
 * <p>Aggregate root over its {@link RuleResult}s: the per-rule audit trail is owned here
 * and persisted by cascade. There is exactly one evaluation per event.
 */
@Entity
@Table(name = "fraud_evaluation")
public class FraudEvaluation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne
	@JoinColumn(name = "transaction_event_id", nullable = false, unique = true, updatable = false)
	private TransactionEvent transactionEvent;

	@Column(nullable = false)
	private boolean flagged;

	@Column(nullable = false)
	private int score;

	@Column(name = "evaluated_at", nullable = false, updatable = false)
	private Instant evaluatedAt;

	@OneToMany(mappedBy = "evaluation", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<RuleResult> ruleResults = new ArrayList<>();

	protected FraudEvaluation() {
		// for JPA
	}

	public FraudEvaluation(TransactionEvent transactionEvent, boolean flagged, int score) {
		this.transactionEvent = transactionEvent;
		this.flagged = flagged;
		this.score = score;
		this.evaluatedAt = Instant.now();
	}

	/** Attaches a rule result to this evaluation, keeping both sides of the relationship in sync. */
	public void addRuleResult(RuleResult ruleResult) {
		ruleResult.setEvaluation(this);
		this.ruleResults.add(ruleResult);
	}

	public Long getId() {
		return id;
	}

	public TransactionEvent getTransactionEvent() {
		return transactionEvent;
	}

	public boolean isFlagged() {
		return flagged;
	}

	public int getScore() {
		return score;
	}

	public Instant getEvaluatedAt() {
		return evaluatedAt;
	}

	public List<RuleResult> getRuleResults() {
		return Collections.unmodifiableList(ruleResults);
	}
}
