package com.capitech.fraud.messaging;

import com.capitech.fraud.domain.TransactionCategory;
import com.capitech.fraud.domain.TransactionEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A categorized transaction event consumed from the {@code transactions} topic — the sole
 * ingestion path (ADR-0004). Deserialized from the JSON record by the value deserializer
 * configured in {@code application.yaml} and validated against these Bean Validation
 * constraints by {@link TransactionEventConsumer}.
 *
 * <p>{@code eventId} is producer-supplied and is the idempotency key: replaying the same
 * id returns the existing evaluation rather than re-processing (ADR-0001, ADR-0002), which
 * absorbs Kafka's at-least-once redelivery.
 */
public record TransactionEventMessage(

		@NotNull(message = "eventId is required")
		UUID eventId,

		@NotBlank @Size(max = 64)
		String transactionId,

		@NotBlank @Size(max = 64)
		String customerId,

		@NotNull @Positive
		BigDecimal amount,

		@NotNull
		@Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO 4217 code")
		String currency,

		@NotNull(message = "category is required")
		TransactionCategory category,

		@Size(max = 140)
		String merchant,

		@Pattern(regexp = "[A-Z]{2}", message = "country must be a 2-letter ISO 3166-1 code")
		String country,

		@NotNull
		Instant occurredAt) {

	/** Maps this message to a domain entity ready for ingestion. */
	public TransactionEvent toEntity() {
		return new TransactionEvent(eventId, transactionId, customerId, amount,
				currency, category, merchant, country, occurredAt);
	}
}
