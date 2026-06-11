package com.capitech.fraud.messaging;

/**
 * A consumed message deserialized cleanly but failed Bean Validation — the same constraints
 * the REST path enforces (ADR-0004). Thrown by {@link TransactionEventConsumer} and
 * classified as non-retryable so the record is dead-lettered on first encounter rather than
 * retried pointlessly.
 */
class InvalidTransactionEventException extends RuntimeException {

	InvalidTransactionEventException(String message) {
		super(message);
	}
}
