--liquibase formatted sql

--changeset ledgerflow:004-create-ledger-entries
--comment: Append-only double-entry ledger
CREATE TABLE ledger_entries (
    id             UUID           NOT NULL DEFAULT gen_random_uuid(),
    transaction_id UUID           NOT NULL,
    account_id     UUID           NOT NULL,
    entry_type     VARCHAR(10)    NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    balance_after  NUMERIC(19, 4) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_ledger_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id)
        REFERENCES accounts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_ledger_balance_after_non_negative CHECK (balance_after >= 0),
    -- exactly one DEBIT and one CREDIT per transaction; its leading column also serves lookups by transaction_id
    CONSTRAINT uk_ledger_transaction_entry_type UNIQUE (transaction_id, entry_type)
);

-- Account statement, newest first
CREATE INDEX idx_ledger_account_created ON ledger_entries (account_id, created_at DESC);
--rollback DROP TABLE ledger_entries;
