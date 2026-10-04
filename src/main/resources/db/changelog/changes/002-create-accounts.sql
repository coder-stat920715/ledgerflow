--liquibase formatted sql

--changeset ledgerflow:002-account-number-sequence
--comment: Source for generated public account numbers
CREATE SEQUENCE account_number_seq START WITH 1000000001 INCREMENT BY 1 NO CYCLE;
--rollback DROP SEQUENCE account_number_seq;

--changeset ledgerflow:002-create-accounts
--comment: Customer accounts
CREATE TABLE accounts (
    id             UUID           NOT NULL DEFAULT gen_random_uuid(),
    account_number VARCHAR(20)    NOT NULL,
    owner_id       UUID           NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    balance        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    status         VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version        BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT uk_accounts_account_number UNIQUE (account_number),
    CONSTRAINT fk_accounts_owner FOREIGN KEY (owner_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_accounts_number_format CHECK (account_number ~ '^[A-Z0-9]{8,20}$'),
    CONSTRAINT ck_accounts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_accounts_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT ck_accounts_closed_zero_balance CHECK (status <> 'CLOSED' OR balance = 0)
);

CREATE INDEX idx_accounts_owner_status ON accounts (owner_id, status);
--rollback DROP TABLE accounts;
