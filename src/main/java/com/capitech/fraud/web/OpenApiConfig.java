package com.capitech.fraud.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level metadata for the generated OpenAPI document and the Swagger UI page
 * ({@code /swagger-ui/index.html}). Per-operation and per-field docs live as annotations
 * on {@link EvaluationController} and the response records.
 */
@Configuration
class OpenApiConfig {

	@Bean
	OpenAPI fraudRuleEngineOpenApi(@Value("${spring.application.name}") String appName) {
		return new OpenAPI()
				.info(new Info()
						.title("Fraud Rule Engine API")
						.description("""
								Fraud evaluations produced by the rule engine.

								Transactions are ingested from Kafka, scored against a configurable set of \
								fraud rules, and persisted. An evaluation is **flagged** once the summed rule \
								score reaches the configured flag threshold. These endpoints expose the stored \
								decisions.""")
						.version("v1"))
				.tags(List.of(new Tag()
						.name("Evaluations")
						.description("Retrieve stored fraud evaluations (" + appName + ").")));
	}
}
