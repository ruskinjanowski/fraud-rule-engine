package com.capitech.fraud.rule;

import com.capitech.fraud.domain.TransactionEvent;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Fires when the event's {@code occurredAt}, viewed in the configured zone, falls inside
 * the night window. Stolen credentials are heavily used while the victim sleeps and can't
 * see notifications — deliberately low-scoring, because legitimate night activity exists:
 * its job is to corroborate, not convict (ADR-0003).
 */
@Component
public class OddHoursRule implements Rule {

	static final String CODE = "ODD_HOURS";
	static final String VERSION = "1";

	private final boolean enabled;
	private final ZoneId zone;
	private final LocalTime nightStart;
	private final LocalTime nightEnd;
	private final int score;

	public OddHoursRule(RuleProperties properties) {
		RuleProperties.OddHours config = properties.oddHours();
		this.enabled = config.enabled();
		this.zone = ZoneId.of(config.zone());
		this.nightStart = LocalTime.parse(config.nightStart());
		this.nightEnd = LocalTime.parse(config.nightEnd());
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
		LocalTime time = LocalTime.ofInstant(event.getOccurredAt(), zone);
		if (inNightWindow(time)) {
			return RuleOutcome.hit(score, "%s in %s falls within night window %s-%s"
					.formatted(time, zone, nightStart, nightEnd));
		}
		return RuleOutcome.miss("%s in %s outside night window %s-%s"
				.formatted(time, zone, nightStart, nightEnd));
	}

	/** Window is [start, end); when start is after end it wraps past midnight (e.g. 22:00-06:00). */
	private boolean inNightWindow(LocalTime time) {
		if (nightStart.isBefore(nightEnd)) {
			return !time.isBefore(nightStart) && time.isBefore(nightEnd);
		}
		return !time.isBefore(nightStart) || time.isBefore(nightEnd);
	}
}
