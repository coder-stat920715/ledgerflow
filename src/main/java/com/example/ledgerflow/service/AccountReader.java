package com.example.ledgerflow.service;

import com.example.ledgerflow.config.CacheNames;
import com.example.ledgerflow.dto.response.AccountResponse;
import com.example.ledgerflow.dto.response.BalanceResponse;
import com.example.ledgerflow.exception.ResourceNotFoundException;
import com.example.ledgerflow.mapper.AccountMapper;
import com.example.ledgerflow.repository.AccountRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached read side. Lives in its own bean so the cache proxy is not bypassed by
 * self-invocation. Authorization is applied by the caller, never inside the cache.
 */
@Service
@RequiredArgsConstructor
public class AccountReader {

    private static final int MAX_ACCOUNTS_PER_USER = 100;

    private final AccountRepository accountRepository;
    private final AccountMapper accountMapper;

    @Cacheable(cacheNames = CacheNames.ACCOUNTS, key = "#id.toString()")
    @Transactional(readOnly = true)
    public AccountResponse findResponse(UUID id) {
        return accountRepository.findById(id)
                .map(accountMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }

    @Cacheable(cacheNames = CacheNames.ACCOUNT_BALANCES, key = "#id.toString()")
    @Transactional(readOnly = true)
    public BalanceResponse findBalance(UUID id) {
        return accountRepository.findById(id)
                .map(accountMapper::toBalanceResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }

    @Cacheable(cacheNames = CacheNames.USER_ACCOUNTS, key = "#ownerId.toString()")
    @Transactional(readOnly = true)
    public List<AccountResponse> findByOwner(UUID ownerId) {
        return accountRepository
                .findByOwnerId(ownerId, PageRequest.of(0, MAX_ACCOUNTS_PER_USER, Sort.by("createdAt")))
                .map(accountMapper::toResponse)
                .getContent();
    }
}
