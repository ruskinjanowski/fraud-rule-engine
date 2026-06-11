package com.capitech.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A categorized transaction event ingested by the engine.
 *
 * <p>{@code eventId} is the producer-supplied identity and is unique — it is the
 * idempotency key that keeps at-least-once Kafka delivery from storing duplicates.
 */
@Entity
@Table(name = "transaction_event")
public class TransactionEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false, unique = true, updatable = false)
	private UUID eventId;

	@Column(name = "transaction_id", nullable = false, length = 64)
	private String transactionId;

	@Column(name = "customer_id", nullable = false, length = 64)
	private String customerId;

	@Column(nullable = false, precision = 19, scale = 4)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private TransactionCategory category;

	@Column(length = 140)
	private String merchant;

	@Column(length = 2)
	private String country;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "received_at", nullable = false, updatable = false)
	private Instant receivedAt;

	protected TransactionEvent() {
		// for JPA
	}

	public TransactionEvent(UUID eventId, String transactionId, String customerId, BigDecimal amount,
			String currency, TransactionCategory category, String merchant, String country, Instant occurredAt) {
		this.eventId = eventId;
		this.transactionId = transactionId;
		this.customerId = customerId;
		this.amount = amount;
		this.currency = currency;
		this.category = category;
		this.merchant = merchant;
		this.country = country;
		this.occurredAt = occurredAt;
		this.receivedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public UUID getEventId() {
		return eventId;
	}

	public String getTransactionId() {
		return transactionId;
	}

	public String getCustomerId() {
		return customerId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public TransactionCategory getCategory() {
		return category;
	}

	public String getMerchant() {
		return merchant;
	}

	public String getCountry() {
		return country;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public Instant getReceivedAt() {
		return receivedAt;
	}
}
