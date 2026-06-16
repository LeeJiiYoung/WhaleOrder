package com.whale.order.domain.order.controller;

import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.service.OrderQueueService;
import com.whale.order.domain.order.service.OrderService;
import com.whale.order.domain.order.service.OrderSseService;
import com.whale.order.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * 주문 대기열 및 SSE 구독 API.
 *
 * <p>주문 생성 후 클라이언트가 처리 결과를 비동기로 수신하기 위한 SSE 엔드포인트를 제공한다.
 * SSE 관련 엔드포인트는 Swagger UI에 노출하지 않는다({@code @Hidden}).</p>
 *
 * <pre>
 * 주문 생성(POST /api/orders)
 *   └─▶ 재고 처리 결과 대기: GET /api/orders/{orderId}/result      (1회성 결과 수신)
 *   └─▶ 상태 변경 실시간 구독: GET /api/orders/{orderId}/updates   (접수→제조→완료 알림)
 *   └─▶ 대기 순서 폴링: GET /api/orders/{orderId}/queue-position
 * </pre>
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderQueueController {

    private final OrderService orderService;
    private final OrderQueueService orderQueueService;
    private final OrderSseService orderSseService;

    /**
     * Kafka Consumer의 재고 처리 결과를 SSE로 수신한다.
     *
     * <p>이미 처리 완료된 주문이면 즉시 결과를 전송하고 연결을 종료한다.
     * 아직 PENDING 상태면 SSE 연결을 유지하고 워커 완료 시 알림을 받는다.</p>
     *
     * @param userDetails 인증된 회원 정보 (본인 주문 여부 검증)
     * @param orderId     결과를 수신할 주문 ID
     * @return SSE 연결 — {@code result} 이벤트로 {@code SUCCESS} 또는 {@code FAILED} 전송
     */
    @Hidden
    @GetMapping(value = "/{orderId}/result", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter waitForResult(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {

        Long memberId = Long.parseLong(userDetails.getUsername());
        Orders order = orderService.findOrderForSse(orderId, memberId);

        // 이미 처리 완료된 경우: 즉시 응답 후 종료
        if (order.getStatus() != OrderStatus.PENDING) {
            SseEmitter emitter = new SseEmitter();
            try {
                String statusMsg = order.getStatus() == OrderStatus.CANCELLED ? "FAILED" : "SUCCESS";
                emitter.send(SseEmitter.event().name("result").data(
                        "{\"status\":\"" + statusMsg + "\",\"orderId\":" + orderId + "}"
                ));
                emitter.complete();
            } catch (IOException ignored) {}
            return emitter;
        }

        // 아직 대기 중: SSE 연결 유지하고 워커 알림 대기
        return orderSseService.register(orderId);
    }

    /**
     * 주문 상태 변경(접수 확인·제조 중·완료)을 SSE로 실시간 구독한다.
     *
     * <p>어드민이 주문 상태를 변경할 때마다 이 채널로 알림이 전송된다.
     * 연결 전에 소유권 검증을 수행하며, 타인의 주문 ID 입력 시 예외가 발생한다.</p>
     *
     * @param userDetails 인증된 회원 정보
     * @param orderId     구독할 주문 ID
     * @return SSE 연결 ({@code text/event-stream})
     */
    @Hidden
    @GetMapping(value = "/{orderId}/updates", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeStatusUpdates(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {

        Long memberId = Long.parseLong(userDetails.getUsername());
        orderService.findOrderForSse(orderId, memberId);

        return orderSseService.registerStatusStream(orderId);
    }

    /**
     * 현재 대기 순서를 조회한다.
     *
     * <p>클라이언트가 폴링 방식으로 대기 순서를 확인할 때 사용한다.
     * {@code position}이 음수이면 이미 처리 완료된 주문이다.</p>
     *
     * @param userDetails 인증된 회원 정보
     * @param orderId     조회할 주문 ID
     * @return 대기 순서({@code position})와 안내 메시지({@code message})
     */
    @GetMapping("/{orderId}/queue-position")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQueuePosition(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {

        long position = orderQueueService.getPosition(orderId);
        String message = position < 0 ? "처리 완료되었습니다." : position + "번째 대기 중입니다.";
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", Map.of("position", position, "message", message)));
    }
}
