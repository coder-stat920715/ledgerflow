--liquibase formatted sql

--changeset ledgerflow:003-create-transactions
--comment: Money movements and their lifecycle status
CREATE TABLE transactions (
    id                     UUID           NOT NULL DEFAULT gen_random_uuid(),
    reference              VARCHAR(36)    NOT NULL,
    idempotency_key        VARCHAR(64)    NOT NULL,
    initiated_by           UUID           NOT NULL,
    source_account_id      UUID           NOT NULL,
    destination_account_id UUID           NOT NULL,
    amount                 NUMERIC(19, 4) NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    type                   VARCHAR(20)    NOT NULL DEFAULT 'TRANSFER',
    status                 VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    description            VARCHAR(255),
    failure_reason         VARCHAR(255),
    reversal_of            UUID,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,
    version                BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT uk_transactions_reference UNIQUE (reference),
    CONSTRAINT uk_transactions_idempotency UNIQUE (initiated_by, idempotency_key),
    CONSTRAINT fk_transactions_initiator FOREIGN KEY (initiated_by)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transactions_source FOREIGN KEY (source_account_id)
        REFERENCES accounts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transactions_destination FOREIGN KEY (destination_account_id)
        REFERENCES accounts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transactions_reversal_of FOREIGN KEY (reversal_of)
        REFERENCES transactions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transactions_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT ck_transactions_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_transactions_type CHECK (type IN ('TRANSFER', 'REVERSAL')),
    CONSTRAINT ck_transactions_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REVERSED')),
    CONSTRAINT ck_transactions_reversal_link CHECK ((type = 'REVERSAL') = (reversal_of IS NOT NULL)),
    CONSTRAINT ck_transactions_completed_at CHECK (status NOT IN ('COMPLETED', 'REVERSED') OR completed_at IS NOT NULL)
);

-- Outgoing / incoming history, newest first
CREATE INDEX idx_transactions_source_created ON transactions (source_account_id, created_at DESC);
CREATE INDEX idx_transactions_destination_created ON transactions (destination_account_id, created_at DESC);

-- Operational monitoring: stuck PENDING / PROCESSING rows
CREATE INDEX idx_transactions_status_created ON transactions (status, created_at);

-- A transaction can be reversed at most once
CREATE UNIQUE INDEX uk_transactions_reversal_of ON transactions (reversal_of) WHERE reversal_of IS NOT NULL;
--rollback DROP TABLE transactions;
