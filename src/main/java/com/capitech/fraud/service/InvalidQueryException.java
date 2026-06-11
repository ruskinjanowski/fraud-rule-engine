package com.capitech.fraud.service;

/** Thrown when retrieval query parameters are inconsistent (e.g. from after to). Mapped to HTTP 400. */
public class InvalidQueryException extends RuntimeException {

	public InvalidQueryException(String message) {
		super(message);
	}
}
