package com.capitech.fraud.service;

import java.util.UUID;

/** Thrown when no stored evaluation exists for a given event id. Mapped to HTTP 404. */
public class EvaluationNotFoundException extends RuntimeException {

	public EvaluationNotFoundException(UUID eventId) {
		super("No evaluation found for event " + eventId);
	}
}
