package com.capitech.fraud.messaging;

import com.capitech.fraud.metrics.FraudMetrics;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Wires the consumer's failure handling (ADR-0004). Un-processable records are routed to
 * {@code <topic>-dlt} (Spring Kafka's default dead-letter name) instead of blocking the
 * partition: transient failures are retried a bounded number of times, while malformed JSON
 * and validation failures are non-retryable and dead-lettered on first encounter.
 *
 * <p>Everything else (consumer/producer factories, listener container, {@code KafkaTemplate},
 * topic creation via {@code KafkaAdmin}) is Spring Boot autoconfiguration driven by
 * {@code application.yaml}.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfig {

	@Bean
	NewTopic transactionsTopic(@Value("${fraud.kafka.transactions-topic}") String topic) {
		return TopicBuilder.name(topic).partitions(1).replicas(1).build();
	}

	@Bean
	NewTopic transactionsDltTopic(@Value("${fraud.kafka.transactions-topic}") String topic) {
		return TopicBuilder.name(topic + "-dlt").partitions(1).replicas(1).build();
	}

	/**
	 * Republishes failed records to {@code <topic>-dlt} (the recoverer's default destination,
	 * same partition) with the original payload preserved. Two templates because the failed
	 * value's type differs by failure: a {@code DeserializationException} leaves the raw
	 * {@code byte[]} payload (republished verbatim), while a validation failure holds an
	 * already-deserialized {@link TransactionEventMessage} (republished as JSON).
	 */
	@Bean
	DefaultErrorHandler kafkaErrorHandler(ProducerFactory<?, ?> producerFactory, FraudMetrics metrics) {
		Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
		templates.put(byte[].class, new KafkaTemplate<>(producerFactory,
				Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));
		templates.put(Object.class, new KafkaTemplate<>(producerFactory,
				Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class)));
		var recoverer = new DeadLetterPublishingRecoverer(templates);
		// Count every record that gets dead-lettered, then publish it. The counter
		// wraps the recoverer so it only fires once a record is actually given up on, not on
		// the intermediate retries.
		ConsumerRecordRecoverer countingRecoverer = (record, exception) -> {
			metrics.recordDeadLetter();
			recoverer.accept(record, exception);
		};
		// Retry transient failures twice (200ms apart). DeserializationException (malformed
		// JSON) is fatal to DefaultErrorHandler out of the box; validation failures are
		// classified likewise — both skip straight to the DLT.
		var handler = new DefaultErrorHandler(countingRecoverer, new FixedBackOff(200L, 2));
		handler.addNotRetryableExceptions(InvalidTransactionEventException.class);
		return handler;
	}
}
