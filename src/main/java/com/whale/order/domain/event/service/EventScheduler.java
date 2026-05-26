package com.whale.order.domain.event.service;

import com.whale.order.domain.event.entity.Event;
import com.whale.order.domain.event.entity.EventStatus;
import com.whale.order.domain.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 이벤트 대기열 처리 스케줄러
 *
 * - openPendingEvents: openAt 도래한 SCHEDULED 이벤트 자동 오픈 (10초마다)
 * - processQueues: OPEN 이벤트 대기열에서 구매 권한 부여 + SSE 알림 (3초마다)
 * - pushPositionUpdates: 대기 중인 사용자에게 현재 순번 푸시 (5초마다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventScheduler {

    private final EventRepository eventRepository;
    private final EventQueueService eventQueueService;
    private final EventQueueFacade eventQueueFacade;
    private final EventPurchaseService eventPurchaseService;
    private final SseEmitterRegistry sseEmitterRegistry;

    // 한 번에 구매 권한을 부여할 최대 인원
    private static final int BATCH_SIZE = 10;

    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void openPendingEvents() {
        List<Event> toOpen = eventRepository.findScheduledEventsToOpen(LocalDateTime.now());
        for (Event event : toOpen) {
            event.open();
            eventQueueFacade.initRateLimiter(event.getEventId());
            log.info("이벤트 자동 오픈 eventId={} name={}", event.getEventId(), event.getName());
        }
    }

    @Scheduled(fixedDelay = 3_000)
    @Transactional(readOnly = true)
    public void processQueues() {
        List<Event> openEvents = eventRepository.findByStatus(EventStatus.OPEN);
        for (Event event : openEvents) {
            int toPoll = Math.min(event.getRemainingCapacity(), BATCH_SIZE);
            if (toPoll <= 0) continue;

            Collection<Long> memberIds = eventQueueService.pollNext(event.getEventId(), toPoll);
            for (Long memberId : memberIds) {
                eventPurchaseService.markReady(event.getEventId(), memberId);
                pushPurchaseReady(memberId, event.getEventId());
            }
        }
    }

    @Scheduled(fixedDelay = 5_000)
    @Transactional(readOnly = true)
    public void pushPositionUpdates() {
        List<Event> openEvents = eventRepository.findByStatus(EventStatus.OPEN);
        for (Event event : openEvents) {
            Collection<Long> allMembers = eventQueueService.getAllMemberIds(event.getEventId());
            for (Long memberId : allMembers) {
                SseEmitter emitter = sseEmitterRegistry.get(memberId);
                if (emitter == null) continue;

                int position = eventQueueService.getPosition(event.getEventId(), memberId);
                try {
                    emitter.send(SseEmitter.event()
                            .name("queue")
                            .data("{\"position\":" + position + "}"));
                } catch (IOException e) {
                    sseEmitterRegistry.remove(memberId);
                }
            }
        }
    }

    private void pushPurchaseReady(Long memberId, Long eventId) {
        SseEmitter emitter = sseEmitterRegistry.get(memberId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name("purchaseReady")
                    .data("{\"eventId\":" + eventId + ",\"message\":\"구매 가능합니다! 5분 안에 구매해주세요.\"}"));
        } catch (IOException e) {
            sseEmitterRegistry.remove(memberId);
        }
    }
}
