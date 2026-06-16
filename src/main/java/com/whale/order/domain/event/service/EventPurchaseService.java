package com.whale.order.domain.event.service;

import com.whale.order.domain.event.entity.Event;
import com.whale.order.domain.event.entity.EventPurchase;
import com.whale.order.domain.event.repository.EventPurchaseRepository;
import com.whale.order.domain.event.repository.EventRepository;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 이벤트 굿즈 구매 권한 관리 + 실제 구매 처리
 *
 * 구매 권한 키: event:ready:{eventId}:{memberId}, TTL 5분
 * Scheduler가 대기열에서 꺼낸 멤버에게 markReady()로 권한 부여 후 SSE 알림.
 * 사용자가 구매 API 호출 시 권한 확인 → 비관락으로 재고 차감 → EventPurchase 저장.
 */
@Service
@RequiredArgsConstructor
public class EventPurchaseService {

    private final EventRepository eventRepository;
    private final EventPurchaseRepository eventPurchaseRepository;
    private final MemberRepository memberRepository;
    private final RedissonClient redissonClient;

    private static final String READY_KEY_PREFIX = "event:ready:";
    private static final Duration READY_TTL = Duration.ofMinutes(5);

    // Scheduler 호출 — 구매 권한 부여 (5분 TTL)
    public void markReady(Long eventId, Long memberId) {
        redissonClient.<String>getBucket(readyKey(eventId, memberId))
                .set("1", READY_TTL);
    }

    public boolean isReady(Long eventId, Long memberId) {
        return redissonClient.<String>getBucket(readyKey(eventId, memberId)).get() != null;
    }

    public boolean isPurchased(Long eventId, Long memberId) {
        return eventPurchaseRepository.existsByEvent_EventIdAndMember_MemberId(eventId, memberId);
    }

    @Transactional
    public void purchase(Long eventId, Long memberId) {
        RBucket<String> readyBucket = redissonClient.getBucket(readyKey(eventId, memberId));
        if (readyBucket.get() == null) {
            throw new IllegalStateException("구매 권한이 없거나 시간이 만료되었습니다 (5분 초과)");
        }

        // 비관락 획득 후 중복 구매 체크 — 락 없이 먼저 조회하면 race condition 발생
        Event event = eventRepository.findWithLock(eventId)
                .orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다"));

        if (eventPurchaseRepository.existsByEvent_EventIdAndMember_MemberId(eventId, memberId)) {
            throw new IllegalStateException("이미 구매한 이벤트입니다");
        }

        event.deductStock(1);

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다"));

        eventPurchaseRepository.save(EventPurchase.builder()
                .event(event)
                .member(member)
                .build());

        readyBucket.delete();
    }

    private String readyKey(Long eventId, Long memberId) {
        return READY_KEY_PREFIX + eventId + ":" + memberId;
    }
}
