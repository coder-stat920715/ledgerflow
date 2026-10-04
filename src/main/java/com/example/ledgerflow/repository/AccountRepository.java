package com.example.ledgerflow.repository;

import com.example.ledgerflow.domain.entity.Account;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    /** Served by idx_accounts_owner_status. */
    Page<Account> findByOwnerId(UUID ownerId, Pageable pageable);

    /** Deterministic ordering so concurrent transactions touch rows in the same sequence. */
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> findAllByIdsOrdered(@Param("ids") Collection<UUID> ids);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    Long nextAccountNumberSequence();
}
