--liquibase formatted sql

--changeset ledgerflow:005-ledger-immutability splitStatements:false
--comment: Reject UPDATE, DELETE and TRUNCATE on the ledger so history cannot be rewritten
CREATE OR REPLACE FUNCTION ledger_entries_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_reject_mutation();

CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_entries_reject_mutation();
--rollback DROP TRIGGER trg_ledger_entries_no_truncate ON ledger_entries;
--rollback DROP TRIGGER trg_ledger_entries_no_update_delete ON ledger_entries;
--rollback DROP FUNCTION ledger_entries_reject_mutation();
