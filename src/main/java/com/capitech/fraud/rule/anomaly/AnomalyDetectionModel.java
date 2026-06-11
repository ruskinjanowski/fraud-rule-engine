package com.capitech.fraud.rule.anomaly;

import smile.anomaly.IsolationForest;

/**
 * A trained Isolation Forest plus the score threshold above which a transaction is treated
 * as anomalous (ADR-0006). Immutable once built; held as a singleton bean and shared across
 * evaluations (scoring is read-only and thread-safe).
 */
public class AnomalyDetectionModel {

	private final IsolationForest forest;
	private final double threshold;
	private final int trainingSize;

	public AnomalyDetectionModel(IsolationForest forest, double threshold, int trainingSize) {
		this.forest = forest;
		this.threshold = threshold;
		this.trainingSize = trainingSize;
	}

	/**
	 * The anomaly score for a feature vector, in {@code (0,1]}: ~0.5 is normal, values above
	 * the threshold (typically {@code > 0.6}) are anomalous. Higher means more isolated.
	 */
	public double score(double[] features) {
		return forest.score(features);
	}

	/** Whether {@code features} scores above the configured anomaly threshold. */
	public boolean isAnomaly(double[] features) {
		return score(features) > threshold;
	}

	public double threshold() {
		return threshold;
	}

	/** Number of synthetic feature rows the forest was fitted on (for startup logging). */
	public int trainingSize() {
		return trainingSize;
	}
}
