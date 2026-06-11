-- Initial schema for the fraud rule engine (see ADR-0001).
-- Three tables: the ingested event, the per-transaction decision, and the per-rule audit trail.

create table transaction_event (
    id              bigint generated always as identity primary key,
    event_id        uuid           not null,
    transaction_id  varchar(64)    not null,
    customer_id     varchar(64)    not null,
    amount          numeric(19, 4) not null,
    currency        varchar(3)     not null,
    category        varchar(32)    not null,
    merchant        varchar(140),
    country         varchar(2),
    occurred_at     timestamptz    not null,
    received_at     timestamptz    not null default now(),
    constraint uq_transaction_event_event_id unique (event_id)
);

-- Supports velocity rules and "flags by customer over a time range" queries.
create index ix_transaction_event_customer_time
    on transaction_event (customer_id, occurred_at);

create table fraud_evaluation (
    id                   bigint      generated always as identity primary key,
    transaction_event_id bigint      not null,
    flagged              boolean     not null,
    score                integer     not null,
    evaluated_at         timestamptz not null default now(),
    constraint fk_fraud_evaluation_event
        foreign key (transaction_event_id) references transaction_event (id),
    -- One evaluation per event: reprocessing the same event stays idempotent.
    constraint uq_fraud_evaluation_event unique (transaction_event_id)
);

create table rule_result (
    id                  bigint      generated always as identity primary key,
    fraud_evaluation_id bigint      not null,
    rule_code           varchar(64) not null,
    rule_version        varchar(32) not null,
    hit                 boolean     not null,
    score               integer     not null,
    detail              varchar(500),
    constraint fk_rule_result_evaluation
        foreign key (fraud_evaluation_id) references fraud_evaluation (id) on delete cascade
);

create index ix_rule_result_evaluation on rule_result (fraud_evaluation_id);
