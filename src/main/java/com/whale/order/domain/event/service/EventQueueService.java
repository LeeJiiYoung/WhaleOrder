package com.whale.order.domain.event.service;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.Collection;

/**
 * Redis Sorted Set 기반 이벤트 대기열 관리
 *
 * key   : event:queue:{eventId}
 * value : memberId
 * score : 접속 timestamp (밀리초) → 작을수록 먼저 들어온 사람
 */
@Service
@RequiredArgsConstructor
public class EventQueueService {

    private final RedissonClient redissonClient;

    private static final String QUEUE_KEY = "event:queue:";

    // 대기열 등록. 이미 등록된 경우 기존 순번 유지 (add는 이미 있으면 무시)
    public void join(Long eventId, Long memberId) {
        RScoredSortedSet<Long> queue = getQueue(eventId);
        // 이미 대기열에 있으면 중복 등록 안 함
        if (queue.contains(memberId)) {
            return;
        }
        queue.add(System.currentTimeMillis(), memberId);
    }

    // 내 순번 반환 (0번째 = 맨 앞). 대기열에 없으면 -1
    public int getPosition(Long eventId, Long memberId) {
        Integer rank = getQueue(eventId).rank(memberId);
        return rank != null ? rank : -1;
    }

    // 현재 대기열 총 인원 수
    public int getSize(Long eventId) {
        return getQueue(eventId).size();
    }

    // 대기열 맨 앞에서 count명 꺼내기 (Scheduler가 호출)
    public Collection<Long> pollNext(Long eventId, int count) {
        RScoredSortedSet<Long> queue = getQueue(eventId);
        // score 기준 오름차순 (먼저 들어온 순) 0 ~ count-1 범위 조회 후 제거
        Collection<Long> members = queue.valueRange(0, count - 1);
        members.forEach(queue::remove);
        return members;
    }

    // 대기열에 있는지 확인
    public boolean isInQueue(Long eventId, Long memberId) {
        return getQueue(eventId).contains(memberId);
    }

    // 대기열 전체 memberId 조회 (Scheduler가 순번 업데이트 푸시 시 사용)
    public Collection<Long> getAllMemberIds(Long eventId) {
        return getQueue(eventId).readAll();
    }

    // 이벤트 종료 시 대기열 삭제
    public void clear(Long eventId) {
        getQueue(eventId).delete();
    }

    private RScoredSortedSet<Long> getQueue(Long eventId) {
        return redissonClient.getScoredSortedSet(QUEUE_KEY + eventId);
    }
}
