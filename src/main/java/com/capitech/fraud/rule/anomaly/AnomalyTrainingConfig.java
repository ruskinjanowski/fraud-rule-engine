package com.capitech.fraud.rule.anomaly;

import com.capitech.fraud.rule.RuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import smile.anomaly.IsolationForest;

/**
 * Fits the {@link AnomalyDetectionModel} once, at startup, from synthetic training data
 * (ADR-0006). Training is in-process and deterministic (fixed seed), so the model is
 * identical on every boot and there is no model artefact to ship or version. The fitted
 * forest is held as a singleton bean and shared, read-only, across evaluations.
 */
@Configuration
public class AnomalyTrainingConfig {

	private static final Logger log = LoggerFactory.getLogger(AnomalyTrainingConfig.class);

	/** Standard Isolation Forest: auto tree depth, 0.7 sampling rate, no hyperplane extension. */
	@Bean
	AnomalyDetectionModel anomalyDetectionModel(AnomalyFeatureExtractor extractor, RuleProperties properties) {
		RuleProperties.Anomaly config = properties.anomaly();
		double[][] trainingData = SyntheticTrainingData.generate(extractor, config);

		long start = System.currentTimeMillis();
		IsolationForest forest = IsolationForest.fit(trainingData,
				new IsolationForest.Options(config.training().trees(), 0, 0.7, 0));
		log.info("Trained Isolation Forest anomaly model: {} synthetic rows × {} features, "
						+ "{} trees, threshold {} ({} ms)",
				trainingData.length, AnomalyFeatureExtractor.DIMENSION, config.training().trees(),
				config.threshold(), System.currentTimeMillis() - start);

		return new AnomalyDetectionModel(forest, config.threshold(), trainingData.length);
	}
}
