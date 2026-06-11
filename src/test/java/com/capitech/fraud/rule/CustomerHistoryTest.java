package com.capitech.fraud.rule;

import static com.capitech.fraud.rule.TestEvents.ANCHOR;
import static org.assertj.core.api.Assertions.assertThat;

import com.capitech.fraud.domain.TransactionEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerHistoryTest {

	@Test
	void withinFiltersToWindowAndSortsNewestFirst() {
		TransactionEvent oneMinuteAgo = TestEvents.event().minutesBeforeAnchor(1).build();
		TransactionEvent fiveMinutesAgo = TestEvents.event().minutesBeforeAnchor(5).build();
		TransactionEvent twentyMinutesAgo = TestEvents.event().minutesBeforeAnchor(20).build();
		CustomerHistory history = new CustomerHistory(ANCHOR,
				List.of(fiveMinutesAgo, twentyMinutesAgo, oneMinuteAgo));

		assertThat(history.within(Duration.ofMinutes(10)))
				.containsExactly(oneMinuteAgo, fiveMinutesAgo);
	}

	@Test
	void windowBoundsAreInclusive() {
		TransactionEvent atWindowEdge = TestEvents.event().minutesBeforeAnchor(10).build();
		TransactionEvent atAnchor = TestEvents.event().minutesBeforeAnchor(0).build();
		CustomerHistory history = new CustomerHistory(ANCHOR, List.of(atWindowEdge, atAnchor));

		assertThat(history.within(Duration.ofMinutes(10)))
				.containsExactly(atAnchor, atWindowEdge);
	}

	@Test
	void noneIsEmptyForAnyWindow() {
		assertThat(CustomerHistory.none(ANCHOR).within(Duration.ofHours(4))).isEmpty();
	}
}
