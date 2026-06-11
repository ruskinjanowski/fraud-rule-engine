package com.capitech.fraud.rule;

import com.capitech.fraud.domain.TransactionEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The customer's recent prior events, loaded once per evaluation and shared by every
 * stateful rule (ADR-0003).
 *
 * <p>Windows are anchored at the evaluated event's {@code occurredAt} (event time, not
 * processing time), so re-evaluating the same event yields the same answer. The event
 * under evaluation is never included — the loader excludes it by {@code eventId}, since
 * it is persisted before rules run in the same transaction.
 */
public final class CustomerHistory {

	private final Instant anchor;
	private final List<TransactionEvent> priorEvents;

	/**
	 * @param anchor the evaluated event's {@code occurredAt}; all windows count back from here
	 * @param priorEvents the customer's other events within the loaded lookback, any order
	 */
	public CustomerHistory(Instant anchor, List<TransactionEvent> priorEvents) {
		this.anchor = anchor;
		this.priorEvents = priorEvents.stream()
				.sorted(Comparator.comparing(TransactionEvent::getOccurredAt).reversed())
				.toList();
	}

	/** History for a customer with no prior events (or when no enabled rule needs any). */
	public static CustomerHistory none(Instant anchor) {
		return new CustomerHistory(anchor, List.of());
	}

	/**
	 * Prior events with {@code occurredAt} in {@code [anchor - window, anchor]}, newest
	 * first. Only meaningful up to the lookback the history was loaded with.
	 */
	public List<TransactionEvent> within(Duration window) {
		Instant from = anchor.minus(window);
		return priorEvents.stream()
				.filter(e -> !e.getOccurredAt().isBefore(from) && !e.getOccurredAt().isAfter(anchor))
				.toList();
	}
}
