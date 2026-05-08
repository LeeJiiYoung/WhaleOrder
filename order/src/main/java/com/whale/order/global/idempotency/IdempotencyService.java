package com.whale.order.global.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String PREFIX = "idempotency:";
    // 처리 중 마킹 TTL — 이 시간 안에 응답이 없으면 재시도 허용
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(30);
    // 완료된 결과 보관 TTL
    private static final Duration RESULT_TTL = Duration.ofHours(24);

    private static final String PROCESSING_SENTINEL = "__PROCESSING__";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 처리 중 마킹 (SET NX로 중복 마킹 방지) */
    public boolean markProcessing(String key) {
        Boolean set = redisTemplate.opsForValue()
                .setIfAbsent(redisKey(key), PROCESSING_SENTINEL, PROCESSING_TTL);
        return Boolean.TRUE.equals(set);
    }

    /** 처리 중인지 확인 */
    public boolean isProcessing(String key) {
        return PROCESSING_SENTINEL.equals(redisTemplate.opsForValue().get(redisKey(key)));
    }

    /** 결과 저장 */
    public <T> void saveResult(String key, T result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(redisKey(key), json, RESULT_TTL);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("멱등성 결과 직렬화 실패", e);
        }
    }

    /** 저장된 결과 조회 (없거나 처리 중이면 null) */
    public <T> T getResult(String key, Class<T> type) {
        String value = redisTemplate.opsForValue().get(redisKey(key));
        if (value == null || PROCESSING_SENTINEL.equals(value)) return null;
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("멱등성 결과 역직렬화 실패", e);
        }
    }

    /** 실패 시 키 삭제 (재시도 허용) */
    public void delete(String key) {
        redisTemplate.delete(redisKey(key));
    }

    private String redisKey(String key) {
        return PREFIX + key;
    }
}
