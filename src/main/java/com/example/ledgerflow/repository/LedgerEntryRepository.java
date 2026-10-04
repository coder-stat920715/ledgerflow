package com.example.ledgerflow.repository;

import com.example.ledgerflow.domain.entity.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /** Account statement. Served by idx_ledger_account_created. */
    Page<LedgerEntry> findByAccountId(UUID accountId, Pageable pageable);

    /** Both rows of one transaction. Served by uk_ledger_transaction_entry_type. */
    List<LedgerEntry> findByTransactionId(UUID transactionId);
}
