package com.example.ledgerflow.service;

import com.example.ledgerflow.config.CacheNames;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Evicts account-related caches only after the surrounding transaction commits, so a
 * rolled-back change never evicts and a reader never re-caches pre-commit data.
 * Eviction problems are logged, never thrown: the TTL bounds any staleness.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheEvictionService {

    private final CacheManager cacheManager;

    public void evictAccounts(Collection<UUID> accountIds, Collection<UUID> ownerIds) {
        Set<UUID> accounts = Set.copyOf(accountIds);
        Set<UUID> owners = Set.copyOf(ownerIds);
        Runnable eviction = () -> {
            evict(CacheNames.ACCOUNTS, accounts);
            evict(CacheNames.ACCOUNT_BALANCES, accounts);
            evict(CacheNames.USER_ACCOUNTS, owners);
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eviction.run();
                }
            });
        } else {
            eviction.run();
        }
    }

    private void evict(String cacheName, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                ids.forEach(id -> cache.evict(id.toString()));
            }
        } catch (RuntimeException e) {
            log.warn("Cache eviction failed for {}; entries expire by TTL", cacheName, e);
        }
    }
}
