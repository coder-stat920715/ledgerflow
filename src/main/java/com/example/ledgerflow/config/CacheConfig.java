package com.example.ledgerflow.config;

import com.example.ledgerflow.dto.response.AccountResponse;
import com.example.ledgerflow.dto.response.BalanceResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

/**
 * Redis caches hold DTOs only. Each cache has its own typed serializer (no polymorphic
 * typing, so nothing in Redis can instantiate arbitrary classes) and its own TTL.
 * A Redis failure on read or write is logged and the call falls through to the database.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Duration FALLBACK_TTL = Duration.ofMinutes(5);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, AppProperties properties) {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(properties.cache().keyPrefix())
                .disableCachingNullValues();

        JavaType accountList = mapper.getTypeFactory()
                .constructCollectionType(List.class, AccountResponse.class);

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                CacheNames.ACCOUNTS,
                typed(base, properties, CacheNames.ACCOUNTS,
                        mapper.getTypeFactory().constructType(AccountResponse.class), mapper),
                CacheNames.ACCOUNT_BALANCES,
                typed(base, properties, CacheNames.ACCOUNT_BALANCES,
                        mapper.getTypeFactory().constructType(BalanceResponse.class), mapper),
                CacheNames.USER_ACCOUNTS,
                typed(base, properties, CacheNames.USER_ACCOUNTS, accountList, mapper));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(FALLBACK_TTL))
                .withInitialCacheConfigurations(perCache)
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(false);
    }

    private static RedisCacheConfiguration typed(RedisCacheConfiguration base, AppProperties properties,
                                                 String cacheName, JavaType type, ObjectMapper mapper) {
        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(mapper, type);
        Duration ttl = properties.cache().ttl().getOrDefault(cacheName, FALLBACK_TTL);
        return base.entryTtl(ttl).serializeValuesWith(SerializationPair.fromSerializer(serializer));
    }
}
