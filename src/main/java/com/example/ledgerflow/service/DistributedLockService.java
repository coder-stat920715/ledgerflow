package com.example.ledgerflow.service;

import com.example.ledgerflow.config.AppProperties;
import com.example.ledgerflow.exception.LockAcquisitionException;
import com.example.ledgerflow.exception.ServiceUnavailableException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Redis mutual exclusion: SET NX PX to acquire, Lua compare-and-delete to release.
 * Keys are de-duplicated and acquired in sorted order, so two opposite transfers
 * between the same accounts can never deadlock. Fails closed: if Redis is unreachable
 * the guarded action never runs.
 *
 * The lease is a safety net, not the source of correctness. Account {@code @Version}
 * and {@code CHECK (balance >= 0)} still reject any conflicting write.
 */
@Slf4j
@Service
public class DistributedLockService {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final AppProperties.Lock config;

    public DistributedLockService(StringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.config = properties.lock();
    }

    public <T> T withLocks(Collection<String> resourceIds, Supplier<T> action) {
        List<String> keys = resourceIds.stream()
                .distinct()
                .sorted()
                .map(id -> config.keyPrefix() + id)
                .toList();
        String token = UUID.randomUUID().toString();
        List<String> held = new ArrayList<>(keys.size());
        try {
            for (String key : keys) {
                acquire(key, token);
                held.add(key);
            }
            return action.get();
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                release(held.get(i), token);
            }
        }
    }

    private void acquire(String key, String token) {
        long deadline = System.nanoTime() + config.waitTimeout().toNanos();
        while (true) {
            try {
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, config.leaseTime()))) {
                    return;
                }
            } catch (DataAccessException e) {
                log.error("Redis unavailable while acquiring lock {}", key, e);
                throw new ServiceUnavailableException("Locking service unavailable, please retry later");
            }
            if (System.nanoTime() >= deadline) {
                throw new LockAcquisitionException(key.substring(config.keyPrefix().length()));
            }
            try {
                TimeUnit.MILLISECONDS.sleep(config.retryInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceUnavailableException("Interrupted while waiting for a lock");
            }
        }
    }

    private void release(String key, String token) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key), token);
        } catch (RuntimeException e) {
            // never mask the action's own result; the lease expires on its own
            log.warn("Could not release lock {}; it will expire after the lease time", key, e);
        }
    }
}
