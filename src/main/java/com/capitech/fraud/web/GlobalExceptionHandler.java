package com.capitech.fraud.web;

import com.capitech.fraud.service.EvaluationNotFoundException;
import com.capitech.fraud.service.InvalidQueryException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Translates application and validation failures into RFC 7807 {@link ProblemDetail}
 * responses, so API errors are consistent and machine-readable. Malformed JSON, unknown
 * enum values, and bad path types are already mapped to ProblemDetail by Spring's default
 * handling; this advice adds the domain-specific cases.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(EvaluationNotFoundException.class)
	ProblemDetail handleNotFound(EvaluationNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Evaluation not found");
		return problem;
	}

	@ExceptionHandler(InvalidQueryException.class)
	ProblemDetail handleInvalidQuery(InvalidQueryException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Invalid query parameters");
		return problem;
	}

	/** Constraint violations on request parameters (e.g. the list endpoint's page/size bounds). */
	@ExceptionHandler(HandlerMethodValidationException.class)
	ProblemDetail handleParameterValidation(HandlerMethodValidationException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, "Request validation failed");
		problem.setTitle("Invalid request");
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(
				error -> errors.putIfAbsent(
						result.getMethodParameter().getParameterName(), error.getDefaultMessage())));
		problem.setProperty("errors", errors);
		return problem;
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, "Request validation failed");
		problem.setTitle("Invalid request");
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		problem.setProperty("errors", errors);
		return problem;
	}
}
