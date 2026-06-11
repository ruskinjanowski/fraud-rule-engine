package com.capitech.fraud.messaging;

import com.capitech.fraud.service.FraudEvaluationService;
import com.capitech.fraud.service.FraudEvaluationService.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka ingestion adapter (ADR-0004): the sole ingestion path for transaction events. A thin
 * adapter over {@link FraudEvaluationService} — the processing logic lives in the service, the
 * entry point is just the topic. The REST API is retrieval-only.
 *
 * <p>The message is a JSON document deserialized to {@link TransactionEventMessage} by the
 * value deserializer configured in {@code application.yaml} (an {@code ErrorHandlingDeserializer}
 * over the Jackson 3 {@code JacksonJsonDeserializer} — malformed JSON never reaches this
 * listener; it surfaces as a {@code DeserializationException} and is dead-lettered by the error
 * handler). The record is then validated against its Bean Validation constraints; a violation
 * throws {@link InvalidTransactionEventException}, which is non-retryable and routed to the
 * dead-letter topic. Kafka's at-least-once redelivery is absorbed by the service's idempotency
 * on {@code eventId}.
 */
@Component
class TransactionEventConsumer {

	private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);

	private final FraudEvaluationService service;
	private final Validator validator;

	TransactionEventConsumer(FraudEvaluationService service, Validator validator) {
		this.service = service;
		this.validator = validator;
	}

	@KafkaListener(topics = "${fraud.kafka.transactions-topic}")
	void onMessage(TransactionEventMessage request) {
		Set<ConstraintViolation<TransactionEventMessage>> violations = validator.validate(request);
		if (!violations.isEmpty()) {
			throw new InvalidTransactionEventException(describe(violations));
		}

		Result result = service.ingestAndEvaluate(request.toEntity());
		log.info("Consumed event {}: flagged={} score={} ({})", result.evaluation().getTransactionEvent().getEventId(),
				result.evaluation().isFlagged(), result.evaluation().getScore(),
				result.created() ? "evaluated" : "idempotent replay");
	}

	private static String describe(Set<ConstraintViolation<TransactionEventMessage>> violations) {
		return violations.stream()
				.map(v -> v.getPropertyPath() + " " + v.getMessage())
				.collect(Collectors.joining("; ", "invalid transaction event: ", ""));
	}
}
