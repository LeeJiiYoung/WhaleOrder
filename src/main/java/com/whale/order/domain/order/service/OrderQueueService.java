package com.whale.order.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrderQueueService {

    private static final String QUEUE_KEY = "order:queue";

    private final StringRedisTemplate redisTemplate;

    // 대기열 등록, 현재 내 순서(1-based) 반환
    public long enqueue(Long orderId) {
        double score = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(QUEUE_KEY, orderId.toString(), score);
        Long rank = redisTemplate.opsForZSet().rank(QUEUE_KEY, orderId.toString());
        return rank != null ? rank + 1 : 1;
    }

    // 맨 앞 주문 꺼내기 (없으면 null)
    // Redisson ZPOPMIN 버그: 큐가 비어있으면 IndexOutOfBoundsException 발생 → size 먼저 확인
    public Long dequeue() {
        try {
            Long size = redisTemplate.opsForZSet().size(QUEUE_KEY);
            if (size == null || size == 0) return null;

            ZSetOperations.TypedTuple<String> result = redisTemplate.opsForZSet().popMin(QUEUE_KEY);
            if (result == null || result.getValue() == null) return null;
            return Long.parseLong(result.getValue());
        } catch (Exception e) {
            return null;
        }
    }

    // 현재 대기 순서 조회 (대기열에 없으면 -1)
    public long getPosition(Long orderId) {
        Long rank = redisTemplate.opsForZSet().rank(QUEUE_KEY, orderId.toString());
        return rank != null ? rank + 1 : -1;
    }

    // 대기열에서 제거 (취소 시 사용), 제거됐으면 true
    public boolean removeFromQueue(Long orderId) {
        Long removed = redisTemplate.opsForZSet().remove(QUEUE_KEY, orderId.toString());
        return removed != null && removed > 0;
    }
}
