package com.capitech.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * The outcome of a single rule applied during a {@link FraudEvaluation}.
 *
 * <p>One row per rule that ran — including rules that did not fire ({@code hit = false}) —
 * so the evaluation has a complete, replayable audit trail. {@code ruleCode}/{@code ruleVersion}
 * identify the rule as code (there is no rule table yet); recording the version leaves room
 * for rule versioning later.
 */
@Entity
@Table(name = "rule_result")
public class RuleResult {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "fraud_evaluation_id", nullable = false, updatable = false)
	private FraudEvaluation evaluation;

	@Column(name = "rule_code", nullable = false, length = 64)
	private String ruleCode;

	@Column(name = "rule_version", nullable = false, length = 32)
	private String ruleVersion;

	@Column(nullable = false)
	private boolean hit;

	@Column(nullable = false)
	private int score;

	@Column(length = 500)
	private String detail;

	protected RuleResult() {
		// for JPA
	}

	public RuleResult(String ruleCode, String ruleVersion, boolean hit, int score, String detail) {
		this.ruleCode = ruleCode;
		this.ruleVersion = ruleVersion;
		this.hit = hit;
		this.score = score;
		this.detail = detail;
	}

	void setEvaluation(FraudEvaluation evaluation) {
		this.evaluation = evaluation;
	}

	public Long getId() {
		return id;
	}

	public FraudEvaluation getEvaluation() {
		return evaluation;
	}

	public String getRuleCode() {
		return ruleCode;
	}

	public String getRuleVersion() {
		return ruleVersion;
	}

	public boolean isHit() {
		return hit;
	}

	public int getScore() {
		return score;
	}

	public String getDetail() {
		return detail;
	}
}
