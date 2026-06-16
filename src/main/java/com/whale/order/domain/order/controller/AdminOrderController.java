package com.whale.order.domain.order.controller;

import com.whale.order.domain.order.dto.OrderResponse;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.service.OrderService;
import com.whale.order.domain.order.service.OrderSseService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * 관리자 주문 API.
 *
 * <p>ADMIN·OWNER 접근 가능. OWNER는 본인 매장 주문만 조회·처리할 수 있으며,
 * 권한 범위 검증은 서비스 레이어({@code OrderService})에서 수행한다.
 * SSE 스트림({@code /stream})으로 새 주문 발생 시 어드민 화면에 실시간 알림을 수신한다.</p>
 */
@Tag(name = "주문 (관리자)", description = "주문 목록 조회 · 상태 변경 · SSE 실시간 알림")
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;
    private final OrderSseService orderSseService;

    /**
     * 주문 목록을 조회한다.
     *
     * <p>상태 필터를 복수로 지정할 수 있으며, 미입력 시 전체 조회된다.
     * OWNER는 본인 매장 주문만 반환된다.</p>
     *
     * @param statuses    조회할 주문 상태 목록 (PENDING·PREPARING·COMPLETED·CANCELLED)
     * @param userDetails 인증된 관리자 정보
     * @return 주문 목록
     */
    @Operation(summary = "주문 목록 조회", description = "상태 필터 복수 가능 (PENDING, PREPARING, COMPLETED, CANCELLED)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getOrders(
            @RequestParam(required = false) List<OrderStatus> statuses,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long callerId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("조회 성공", orderService.getAllOrders(statuses, callerId)));
    }

    /**
     * 어드민 SSE 스트림에 연결한다.
     *
     * <p>새 주문이 접수될 때마다 연결된 모든 어드민 클라이언트로 브로드캐스트된다.
     * Swagger UI에서는 노출하지 않는다({@code @Hidden}).</p>
     *
     * @return SSE 연결 ({@code text/event-stream})
     */
    @Hidden
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNewOrders() {
        return orderSseService.registerAdmin(UUID.randomUUID().toString());
    }

    /**
     * 주문 상태를 변경한다.
     *
     * <p>상태 전이는 {@code action} 경로 변수로 제어한다.</p>
     * <ul>
     *   <li>{@code prepare} — PENDING → PREPARING (제조 시작)</li>
     *   <li>{@code complete} — PREPARING → COMPLETED (제조 완료)</li>
     * </ul>
     * <p>상태 변경 시 고객 SSE 채널로 실시간 알림이 전송된다.</p>
     *
     * @param orderId     상태를 변경할 주문 ID
     * @param action      {@code prepare} 또는 {@code complete}
     * @param userDetails 인증된 관리자 정보
     * @return 변경된 주문 정보
     */
    @Operation(summary = "주문 상태 변경", description = "action: prepare(제조 시작) · complete(완료)")
    @PatchMapping("/{orderId}/{action}")
    public ResponseEntity<ApiResponse<OrderResponse>> changeStatus(
            @PathVariable Long orderId,
            @PathVariable String action,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long adminId = Long.parseLong(userDetails.getUsername());
        return ResponseEntity.ok(ApiResponse.ok("상태가 변경됐습니다", orderService.changeStatus(orderId, action, adminId)));
    }
}
