package com.whale.order.domain.event.controller;

import com.whale.order.domain.event.dto.*;
import com.whale.order.domain.event.service.EventService;
import com.whale.order.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 어드민용 굿즈/한정 판매 이벤트 관리 컨트롤러.
 */
@Tag(name = "이벤트 (관리자)", description = "굿즈 등록 · 한정 판매 이벤트 생성 · 오픈/종료 처리")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminEventController {

    private final EventService eventService;

    // ── 굿즈 ────────────────────────────────────────────────────────

    /**
     * 굿즈(한정판 상품)를 등록한다.
     */
    @Operation(summary = "굿즈 등록", description = "한정 판매 이벤트에 연결할 굿즈(상품)를 등록한다.")
    @PostMapping("/goods")
    public ResponseEntity<ApiResponse<GoodsResponse>> createGoods(
            @Valid @ModelAttribute GoodsCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("굿즈 등록 완료", eventService.createGoods(request)));
    }

    /**
     * 등록된 굿즈 목록을 조회한다.
     */
    @Operation(summary = "굿즈 목록 조회")
    @GetMapping("/goods")
    public ResponseEntity<ApiResponse<List<GoodsResponse>>> getGoods() {
        return ResponseEntity.ok(ApiResponse.ok("굿즈 목록 조회 완료", eventService.getGoods()));
    }

    // ── 이벤트 ──────────────────────────────────────────────────────

    /**
     * 선착순 한정 판매 이벤트를 등록한다.
     */
    @Operation(summary = "이벤트 등록", description = "선착순 한정 판매 이벤트를 생성한다. open_at 시각에 EventScheduler가 자동 오픈한다.")
    @PostMapping("/events")
    public ResponseEntity<ApiResponse<EventResponse>> createEvent(
            @Valid @RequestBody EventCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("이벤트 등록 완료", eventService.createEvent(request)));
    }

    /**
     * 어드민 관점의 이벤트 목록을 조회한다.
     */
    @Operation(summary = "이벤트 목록 조회 (어드민)", description = "모든 상태의 이벤트 목록을 조회한다.")
    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<EventResponse>>> getEvents() {
        return ResponseEntity.ok(ApiResponse.ok("이벤트 목록 조회 완료", eventService.getAdminEvents()));
    }

    /**
     * 이벤트를 강제로 오픈 상태로 전환한다 (테스트용, 정상 흐름은 EventScheduler가 open_at 시각에 자동 처리).
     */
    @Operation(summary = "이벤트 강제 오픈", description = "이벤트를 즉시 OPEN 상태로 전환한다. 테스트용이며 정상 흐름은 EventScheduler가 처리한다.")
    @PatchMapping("/events/{eventId}/open")
    public ResponseEntity<ApiResponse<Void>> openEvent(@PathVariable Long eventId) {
        eventService.openEvent(eventId);
        return ResponseEntity.ok(ApiResponse.ok("이벤트 오픈 완료", null));
    }

    /**
     * 이벤트를 강제로 종료 상태로 전환한다 (테스트용).
     */
    @Operation(summary = "이벤트 강제 종료", description = "이벤트를 즉시 CLOSED 상태로 전환한다. 테스트용.")
    @PatchMapping("/events/{eventId}/close")
    public ResponseEntity<ApiResponse<Void>> closeEvent(@PathVariable Long eventId) {
        eventService.closeEvent(eventId);
        return ResponseEntity.ok(ApiResponse.ok("이벤트 종료 완료", null));
    }
}
