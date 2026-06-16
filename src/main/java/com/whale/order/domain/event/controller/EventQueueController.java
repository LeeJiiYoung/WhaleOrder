package com.whale.order.domain.event.controller;

import com.whale.order.domain.event.dto.EventResponse;
import com.whale.order.domain.event.service.EventPurchaseService;
import com.whale.order.domain.event.service.EventQueueFacade;
import com.whale.order.domain.event.service.EventQueueService;
import com.whale.order.domain.event.service.EventService;
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

/**
 * 고객용 한정 판매 이벤트 · 선착순 대기열 컨트롤러.
 * 대기열 등록(join) → SSE로 순번 수신 → 구매 가능 알림 → 구매(purchase) 순서로 흐른다.
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventQueueController {

    private final EventService eventService;
    private final EventQueueFacade eventQueueFacade;
    private final EventQueueService eventQueueService;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final EventPurchaseService eventPurchaseService;

    /**
     * 활성 이벤트 목록을 조회한다 (OPEN + SCHEDULED 상태만 포함).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<EventResponse>>> getEvents() {
        return ResponseEntity.ok(ApiResponse.ok("이벤트 목록 조회 성공", eventService.getActiveEvents()));
    }

    /**
     * 이벤트 상세 정보를 조회한다.
     */
    @GetMapping("/{eventId}")
    public ResponseEntity<ApiResponse<EventResponse>> getEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(ApiResponse.ok("이벤트 조회 성공", eventService.getEvent(eventId)));
    }

    /**
     * 내 대기열 상태를 조회한다 (대기 순번, 구매 가능 여부, 구매 완료 여부).
     * 페이지 새로고침 시 클라이언트 상태 복원용으로 쓴다.
     */
    @GetMapping("/{eventId}/queue/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyStatus(
            @PathVariable Long eventId,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long memberId = memberId(userDetails);
        return ResponseEntity.ok(ApiResponse.ok("상태 조회 성공", Map.of(
                "inQueue",    eventQueueService.isInQueue(eventId, memberId),
                "position",   eventQueueService.getPosition(eventId, memberId),
                "isReady",    eventPurchaseService.isReady(eventId, memberId),
                "purchased",  eventPurchaseService.isPurchased(eventId, memberId)
        )));
    }

    /**
     * 이벤트 대기열에 등록한다. 이미 등록돼 있으면 중복 요청으로 보고 그대로 성공 응답한다.
     * RateLimiter 초과 시 429를 반환하며, 클라이언트는 이를 보고 자동 재시도한다.
     */
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

    /**
     * 이벤트 굿즈를 구매한다. Scheduler가 SSE로 구매 가능(purchaseReady) 알림을 보낸 뒤 사용자가 호출한다.
     */
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
