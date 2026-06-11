package com.capitech.fraud.rule;

import com.capitech.fraud.domain.TransactionCategory;
import com.capitech.fraud.domain.TransactionEvent;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Fires when a card-present event's country differs from the customer's most recent
 * located event, closer in time than plausible travel — a card swiped in Johannesburg
 * cannot appear at a POS in London minutes later, so the card is cloned or stolen.
 *
 * <p>Scoped to card-present categories ({@code POS}, {@code ATM}): an {@code ONLINE}
 * purchase from abroad is unremarkable. Country-level granularity is deliberately coarse
 * — one minimum-gap config, no distance matrix (ADR-0003).
 */
@Component
public class ImpossibleTravelRule implements Rule {

	static final String CODE = "IMPOSSIBLE_TRAVEL";
	static final String VERSION = "1";

	private static final Set<TransactionCategory> CARD_PRESENT =
			EnumSet.of(TransactionCategory.POS, TransactionCategory.ATM);

	private final RuleProperties.ImpossibleTravel config;

	public ImpossibleTravelRule(RuleProperties properties) {
		this.config = properties.impossibleTravel();
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
		return config.enabled();
	}

	@Override
	public Duration historyWindow() {
		return config.minGap();
	}

	@Override
	public RuleOutcome evaluate(TransactionEvent event, CustomerHistory history) {
		if (!CARD_PRESENT.contains(event.getCategory())) {
			return RuleOutcome.miss("category %s is not card-present".formatted(event.getCategory()));
		}
		if (event.getCountry() == null) {
			return RuleOutcome.miss("event has no country");
		}
		Optional<TransactionEvent> previous = history.within(config.minGap()).stream()
				.filter(e -> e.getCountry() != null)
				.findFirst();
		if (previous.isEmpty()) {
			return RuleOutcome.miss(
					"no located event within %dmin before this one".formatted(config.minGap().toMinutes()));
		}
		TransactionEvent prior = previous.get();
		if (prior.getCountry().equalsIgnoreCase(event.getCountry())) {
			return RuleOutcome.miss("same country (%s) as previous event".formatted(event.getCountry()));
		}
		long gapMinutes = Duration.between(prior.getOccurredAt(), event.getOccurredAt()).toMinutes();
		return RuleOutcome.hit(config.score(), "country %s only %dmin after %s — below minimum gap %dmin"
				.formatted(event.getCountry(), gapMinutes, prior.getCountry(), config.minGap().toMinutes()));
	}
}
