-- Retrieval query API (ADR-0005): time-range queries that don't name a customer
-- can't use ix_transaction_event_customer_time (customer_id leads that index).
create index ix_transaction_event_occurred_at
    on transaction_event (occurred_at);
