package com.example.ledgerflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ledgerflow.config.AppProperties;
import com.example.ledgerflow.exception.LockAcquisitionException;
import com.example.ledgerflow.exception.ServiceUnavailableException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DistributedLockServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> ops;

    private DistributedLockService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(null,
                new AppProperties.Lock(Duration.ofMillis(100), Duration.ofSeconds(5), Duration.ofMillis(10), "t:"),
                null, null);
        when(redis.opsForValue()).thenReturn(ops);
        service = new DistributedLockService(redis, properties);
    }

    @Test
    void acquiresDeduplicatedKeysInSortedOrderAndReleasesThem() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        String result = service.withLocks(List.of("b", "a", "a"), () -> "done");

        assertThat(result).isEqualTo("done");
        InOrder order = inOrder(ops);
        order.verify(ops).setIfAbsent(eq("t:a"), anyString(), any(Duration.class));
        order.verify(ops).setIfAbsent(eq("t:b"), anyString(), any(Duration.class));
        verify(ops, times(2)).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(redis, times(2)).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void releasesLocksWhenTheActionFails() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThatThrownBy(() -> service.withLocks(List.of("a"), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void failsWithConflictWhenLockCannotBeAcquiredInTime() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> service.withLocks(List.of("a"), () -> "never"))
                .isInstanceOf(LockAcquisitionException.class);

        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void failsClosedWhenRedisIsDown() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.withLocks(List.of("a"), () -> "never"))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
