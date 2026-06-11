package com.capitech.fraud.rule;

import com.capitech.fraud.domain.TransactionEvent;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Fires when the event's country is on a configured high-risk list (e.g. the FATF
 * high-risk jurisdiction list). A static-list corroborator, deliberately low-scoring:
 * geography alone convicts nobody, but it pushes a borderline pattern over the threshold.
 * The list ships empty — populating it is an operations decision, not a code default
 * (ADR-0003).
 */
@Component
public class HighRiskCountryRule implements Rule {

	static final String CODE = "HIGH_RISK_COUNTRY";
	static final String VERSION = "1";

	private final boolean enabled;
	private final Set<String> countries;
	private final int score;

	public HighRiskCountryRule(RuleProperties properties) {
		RuleProperties.HighRiskCountry config = properties.highRiskCountry();
		this.enabled = config.enabled();
		List<String> configured = config.countries() == null ? List.of() : config.countries();
		this.countries = configured.stream()
				.map(String::trim)
				.filter(c -> !c.isEmpty())
				.map(c -> c.toUpperCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());
		this.score = config.score();
	}

	@Override
	public String code() {
		return CODE;
	}

	@Override
	public String version() {
		return VERSION;
	}

	@Override
	public boolean enabled() {
		return enabled;
	}

	@Override
	public RuleOutcome evaluate(TransactionEvent event, CustomerHistory history) {
		if (event.getCountry() == null) {
			return RuleOutcome.miss("event has no country");
		}
		String country = event.getCountry().toUpperCase(Locale.ROOT);
		if (countries.contains(country)) {
			return RuleOutcome.hit(score, "country %s is on the high-risk list".formatted(country));
		}
		return RuleOutcome.miss("country %s not on the high-risk list".formatted(country));
	}
}
