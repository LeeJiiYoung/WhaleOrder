package com.whale.order.domain.event.service;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 대기열 진입 앞단에서 초당 유입량을 제한한다.
 *
 * 이벤트 오픈 순간 수천 명이 동시에 join()을 호출하면
 * Redis에 과부하가 걸리므로, RateLimiter로 초당 N명만 통과시킨다.
 *
 * key : event:ratelimiter:{eventId}
 */
@Component
@RequiredArgsConstructor
public class EventQueueFacade {

    private final RedissonClient redissonClient;
    private final EventQueueService eventQueueService;

    // 초당 허용 인원 (필요시 application.yaml로 외부화 가능)
    private static final long RATE_PER_SECOND = 100;
    private static final String RATE_LIMITER_KEY = "event:ratelimiter:";

    /**
     * 대기열 진입 시도.
     * 초당 허용 인원을 초과하면 false 반환 → 컨트롤러에서 429 응답
     */
    public boolean tryJoin(Long eventId, Long memberId) {
        RRateLimiter rateLimiter = getRateLimiter(eventId);
        // 토큰 1개를 즉시 획득 시도 (대기 없음)
        if (!rateLimiter.tryAcquire()) {
            return false;
        }
        eventQueueService.join(eventId, memberId);
        return true;
    }

    // 이벤트 오픈 시 RateLimiter 초기화 (Scheduler에서 호출)
    public void initRateLimiter(Long eventId) {
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(RATE_LIMITER_KEY + eventId);
        // 이미 초기화된 경우 trySetRate는 false 반환하며 무시됨
        rateLimiter.trySetRate(RateType.OVERALL, RATE_PER_SECOND, 1, RateIntervalUnit.SECONDS);
    }

    // 이벤트 종료 시 RateLimiter + 대기열 정리
    public void cleanup(Long eventId) {
        redissonClient.getRateLimiter(RATE_LIMITER_KEY + eventId).delete();
        eventQueueService.clear(eventId);
    }

    private RRateLimiter getRateLimiter(Long eventId) {
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(RATE_LIMITER_KEY + eventId);
        // 초기화 안 된 경우 lazy 초기화
        rateLimiter.trySetRate(RateType.OVERALL, RATE_PER_SECOND, 1, RateIntervalUnit.SECONDS);
        return rateLimiter;
    }
}
