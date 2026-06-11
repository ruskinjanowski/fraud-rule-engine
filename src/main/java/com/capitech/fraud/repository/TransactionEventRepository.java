package com.capitech.fraud.repository;

import com.capitech.fraud.domain.TransactionEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionEventRepository extends JpaRepository<TransactionEvent, Long> {

	Optional<TransactionEvent> findByEventId(UUID eventId);

	boolean existsByEventId(UUID eventId);

	/**
	 * The customer's prior events for one evaluation's {@code CustomerHistory} (ADR-0003):
	 * everything in {@code [from, to]} except the event under evaluation, which is already
	 * persisted when this runs. Served by {@code ix_transaction_event_customer_time}.
	 */
	List<TransactionEvent> findByCustomerIdAndEventIdNotAndOccurredAtBetweenOrderByOccurredAtDesc(
			String customerId, UUID eventIdNot, Instant from, Instant to);
}
