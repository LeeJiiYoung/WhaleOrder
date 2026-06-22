package com.whale.order.global.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 멱등성 서비스 — Redisson 기반(SET NX EX) 구현.
 *
 * <p>이전 구현(PostgreSQL ON CONFLICT + delete/insert 재획득) 의 race 위험을 제거.
 * PROCESSING TTL 자동 만료 덕에 "30초 PROCESSING 타임아웃 감지 → 재획득" 로직 자체가 사라져
 * 느린 트랜잭션과 새 요청 사이의 중복 처리 가능성이 없다.
 *
 * <p>TTL 정책:
 * <ul>
 *   <li>PROCESSING 60초 — PG 호출 + 여유. 만료 후 새 요청 허용</li>
 *   <li>COMPLETED  60초 — 네트워크 재시도 보호. "동일 카트로 한 잔 더" 시나리오에서 1분 텀이면 충분</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    private static final String PROCESSING_MARKER = "__PROCESSING__";
    private static final String KEY_PREFIX        = "idem:";
    private static final Duration PROCESSING_TTL  = Duration.ofSeconds(60);
    private static final Duration RESULT_TTL      = Duration.ofSeconds(60);

    /**
     * PROCESSING 표시를 원자적으로 설정한다.
     * Redis SET NX EX 한 줄 — 동시 호출 중 정확히 하나만 true.
     * @return true: 처리권 획득, false: 다른 요청이 처리 중이거나 이미 완료됨
     */
    public boolean markProcessing(String key) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + key);
        return bucket.setIfAbsent(PROCESSING_MARKER, PROCESSING_TTL);
    }

    /**
     * 완료된 결과 조회. PROCESSING 상태이거나 키가 없으면 null.
     */
    public <T> T getResult(String key, Class<T> type) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + key);
        String value = bucket.get();
        if (value == null || PROCESSING_MARKER.equals(value)) return null;
        return deserialize(value, type);
    }

    /**
     * 처리 완료 결과 저장 (PROCESSING → COMPLETED).
     * TTL 을 RESULT_TTL 로 갱신해 네트워크 재시도 보호 윈도우를 명시한다.
     */
    public <T> void saveResult(String key, T result) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + key);
        bucket.set(serialize(result), RESULT_TTL);
    }

    /**
     * 실패 시 키 삭제 → 클라이언트 재시도 허용.
     */
    public void delete(String key) {
        redissonClient.getBucket(KEY_PREFIX + key).delete();
    }

    private <T> String serialize(T obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("멱등성 결과 직렬화 실패", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("멱등성 결과 역직렬화 실패", e);
        }
    }
}
