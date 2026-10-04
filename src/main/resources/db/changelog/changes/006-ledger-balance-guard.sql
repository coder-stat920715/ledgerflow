--liquibase formatted sql

--changeset ledgerflow:006-ledger-balance-guard splitStatements:false
--comment: At commit time, debits must equal credits for every transaction that has ledger rows
CREATE OR REPLACE FUNCTION ledger_entries_assert_balanced() RETURNS trigger AS $$
DECLARE
    total_debit  NUMERIC(19, 4);
    total_credit NUMERIC(19, 4);
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE entry_type = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE entry_type = 'CREDIT'), 0)
      INTO total_debit, total_credit
      FROM ledger_entries
     WHERE transaction_id = NEW.transaction_id;

    IF total_debit <> total_credit THEN
        RAISE EXCEPTION 'Unbalanced ledger for transaction %: debit % <> credit %',
            NEW.transaction_id, total_debit, total_credit
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_assert_balanced();
--rollback DROP TRIGGER trg_ledger_entries_balanced ON ledger_entries;
--rollback DROP FUNCTION ledger_entries_assert_balanced();
