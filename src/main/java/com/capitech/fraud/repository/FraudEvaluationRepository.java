package com.capitech.fraud.repository;

import com.capitech.fraud.domain.FraudEvaluation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FraudEvaluationRepository
		extends JpaRepository<FraudEvaluation, Long>, JpaSpecificationExecutor<FraudEvaluation> {

	/**
	 * Loads the full aggregate — event and per-rule audit trail — in one query, so the
	 * response can be serialized after the transaction closes ({@code open-in-view: false}).
	 */
	@EntityGraph(attributePaths = {"transactionEvent", "ruleResults"})
	Optional<FraudEvaluation> findByTransactionEvent_EventId(UUID eventId);

	/**
	 * List query for the retrieval API (ADR-0005): fetches the event eagerly (summary rows
	 * are serialized after the transaction closes) but deliberately not {@code ruleResults} —
	 * paginating a collection fetch would degrade to in-memory pagination.
	 */
	@Override
	@EntityGraph(attributePaths = "transactionEvent")
	Page<FraudEvaluation> findAll(Specification<FraudEvaluation> spec, Pageable pageable);
}
