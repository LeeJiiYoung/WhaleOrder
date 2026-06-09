package com.whale.order.domain.order.controller;

import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.order.service.OrderQueueService;
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

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderQueueController {

    private final OrderQueueService orderQueueService;
    private final OrderSseService orderSseService;
    private final OrderRepository orderRepository;

    // 처리 결과 대기 (SSE)
    @Hidden
    @GetMapping(value = "/{orderId}/result", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter waitForResult(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {

        Orders order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다"));

        Long memberId = Long.parseLong(userDetails.getUsername());
        if (!order.getMember().getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인 주문만 조회할 수 있습니다");
        }

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

    // 상태 변경 실시간 구독 (수락/제조/완료 알림)
    @Hidden
    @GetMapping(value = "/{orderId}/updates", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeStatusUpdates(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {

        Orders order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다"));

        Long memberId = Long.parseLong(userDetails.getUsername());
        if (!order.getMember().getMemberId().equals(memberId)) {
            throw new IllegalArgumentException("본인 주문만 조회할 수 있습니다");
        }

        return orderSseService.registerStatusStream(orderId);
    }

    // 현재 대기 순서 조회
    @GetMapping("/{orderId}/queue-position")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQueuePosition(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long orderId) {

        long position = orderQueueService.getPosition(orderId);
        String message = position < 0 ? "처리 완료되었습니다." : position + "번째 대기 중입니다.";
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", Map.of("position", position, "message", message)));
    }
}
