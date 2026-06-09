package com.whale.order.domain.event.controller;

import com.whale.order.domain.event.dto.EventResponse;
import com.whale.order.domain.event.repository.EventPurchaseRepository;
import com.whale.order.domain.event.repository.EventRepository;
import com.whale.order.domain.event.service.EventPurchaseService;
import com.whale.order.domain.event.service.EventQueueFacade;
import com.whale.order.domain.event.service.EventQueueService;
import com.whale.order.domain.event.service.SseEmitterRegistry;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventQueueController {

    private final EventQueueFacade eventQueueFacade;
    private final EventQueueService eventQueueService;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final EventPurchaseService eventPurchaseService;
    private final EventRepository eventRepository;
    private final EventPurchaseRepository eventPurchaseRepository;

    // 활성 이벤트 목록 (OPEN + SCHEDULED)
    @GetMapping
    public ResponseEntity<ApiResponse<List<EventResponse>>> getEvents() {
        List<EventResponse> events = eventRepository.findActiveEvents()
                .stream().map(EventResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok("이벤트 목록 조회 성공", events));
    }

    // 이벤트 상세
    @GetMapping("/{eventId}")
    public ResponseEntity<ApiResponse<EventResponse>> getEvent(@PathVariable Long eventId) {
        return eventRepository.findById(eventId)
                .map(e -> ResponseEntity.ok(ApiResponse.ok("이벤트 조회 성공", EventResponse.from(e))))
                .orElse(ResponseEntity.notFound().build());
    }

    // 내 대기 상태 조회 (페이지 새로고침 시 복원용)
    @GetMapping("/{eventId}/queue/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyStatus(
            @PathVariable Long eventId,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("상태 조회 성공", Map.of(
                "inQueue", eventQueueService.isInQueue(eventId, memberId),
                "position", eventQueueService.getPosition(eventId, memberId),
                "isReady", eventPurchaseService.isReady(eventId, memberId),
                "purchased", eventPurchaseRepository.existsByEvent_EventIdAndMember_MemberId(eventId, memberId)
        )));
    }

    // 대기열 등록
    @PostMapping("/{eventId}/queue/join")
    public ResponseEntity<ApiResponse<Void>> join(
            @PathVariable Long eventId,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long memberId = memberId(userDetails);

        // 이미 대기열에 있으면 바로 성공 반환 (새로고침 등 중복 요청 처리)
        if (eventQueueService.isInQueue(eventId, memberId)) {
            return ResponseEntity.ok(ApiResponse.ok("이미 대기열에 등록되어 있습니다", null));
        }

        boolean joined = eventQueueFacade.tryJoin(eventId, memberId);
        if (!joined) {
            // RateLimiter 초과 → 프론트에서 자동 재시도
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.fail("잠시 후 다시 시도해주세요"));
        }

        return ResponseEntity.ok(ApiResponse.ok("대기열에 등록됐습니다", null));
    }

    /**
     * SSE 연결. 대기열 등록 후 호출한다.
     * 연결되면 즉시 현재 순번을 전송하고, 이후 Scheduler가 주기적으로 푸시한다.
     * 연결 최대 유지 시간: 30분
     */
    @Hidden
    @GetMapping(value = "/{eventId}/queue/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @PathVariable Long eventId,
            @AuthenticationPrincipal UserDetails userDetails) throws IOException {

        Long memberId = memberId(userDetails);
        // 30분 타임아웃 (이벤트 대기 중 연결 유지)
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        sseEmitterRegistry.register(memberId, emitter);

        // 연결 즉시 현재 순번 전송
        int position = eventQueueService.getPosition(eventId, memberId);
        emitter.send(SseEmitter.event()
                .name("queue")
                .data("{\"position\":" + position + "}"));

        return emitter;
    }

    // 구매 — Scheduler가 SSE로 purchaseReady 알림 후 사용자가 호출
    @PostMapping("/{eventId}/purchase")
    public ResponseEntity<ApiResponse<Void>> purchase(
            @PathVariable Long eventId,
            @AuthenticationPrincipal UserDetails userDetails) {

        eventPurchaseService.purchase(eventId, memberId(userDetails));
        return ResponseEntity.ok(ApiResponse.ok("구매 완료됐습니다", null));
    }

    private Long memberId(UserDetails u) {
        return Long.parseLong(u.getUsername());
    }
}
