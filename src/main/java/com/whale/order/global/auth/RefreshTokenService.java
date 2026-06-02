package com.whale.order.global.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;
    private final long refreshTokenExpiry;

    public RefreshTokenService(
            StringRedisTemplate redisTemplate,
            @Value("${jwt.refresh-token-expiry}") long refreshTokenExpiry
    ) {
        this.redisTemplate = redisTemplate;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    public void save(Long memberId, String refreshToken) {
        redisTemplate.opsForValue().set(
                KEY_PREFIX + memberId,
                refreshToken,
                refreshTokenExpiry,
                TimeUnit.MILLISECONDS
        );
    }

    public String get(Long memberId) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + memberId);
    }

    public void delete(Long memberId) {
        redisTemplate.delete(KEY_PREFIX + memberId);
    }
}
