package com.example.ledgerflow.repository;

import com.example.ledgerflow.domain.entity.Transaction;
import com.example.ledgerflow.domain.enums.TransactionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /** Idempotent replay lookup. Served by uk_transactions_idempotency. */
    Optional<Transaction> findByInitiatedByIdAndIdempotencyKey(UUID initiatedById, String idempotencyKey);

    Optional<Transaction> findByReference(String reference);

    /** Incoming and outgoing history. Callers pass Sort by createdAt desc. */
    @Query("select t from Transaction t "
            + "where t.sourceAccount.id = :accountId or t.destinationAccount.id = :accountId")
    Page<Transaction> findHistoryByAccountId(@Param("accountId") UUID accountId, Pageable pageable);

    /** Serializes concurrent reversals of the same transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t where t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByReversalOfId(UUID reversalOfId);

    /** Operational monitoring. Served by idx_transactions_status_created. */
    List<Transaction> findByStatusAndCreatedAtBefore(TransactionStatus status, Instant createdBefore);
}
