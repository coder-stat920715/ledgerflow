package com.example.ledgerflow.service;

import com.example.ledgerflow.domain.entity.Account;
import com.example.ledgerflow.domain.entity.LedgerEntry;
import com.example.ledgerflow.domain.entity.Transaction;
import com.example.ledgerflow.domain.enums.EntryType;
import com.example.ledgerflow.repository.LedgerEntryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * Posts the DEBIT and CREDIT rows of a transfer. Must be called after both balances
     * were updated, so {@code balance_after} is the final balance. MANDATORY propagation
     * guarantees the rows commit together with the balance changes, never on their own.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> post(Transaction transaction, Account source, Account destination) {
        LedgerEntry debit = LedgerEntry.builder()
                .transaction(transaction)
                .account(source)
                .entryType(EntryType.DEBIT)
                .amount(transaction.getAmount())
                .balanceAfter(source.getBalance())
                .build();
        LedgerEntry credit = LedgerEntry.builder()
                .transaction(transaction)
                .account(destination)
                .entryType(EntryType.CREDIT)
                .amount(transaction.getAmount())
                .balanceAfter(destination.getBalance())
                .build();
        return ledgerEntryRepository.saveAll(List.of(debit, credit));
    }
}
