package com.whale.order.domain.event.controller;

import com.whale.order.domain.event.dto.*;
import com.whale.order.domain.event.service.EventService;
import com.whale.order.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminEventController {

    private final EventService eventService;

    // ── 굿즈 ────────────────────────────────────────────────────────

    @PostMapping("/goods")
    public ResponseEntity<ApiResponse<GoodsResponse>> createGoods(
            @Valid @ModelAttribute GoodsCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("굿즈 등록 완료", eventService.createGoods(request)));
    }

    @GetMapping("/goods")
    public ResponseEntity<ApiResponse<List<GoodsResponse>>> getGoods() {
        return ResponseEntity.ok(ApiResponse.ok("굿즈 목록 조회 완료", eventService.getGoods()));
    }

    // ── 이벤트 ──────────────────────────────────────────────────────

    @PostMapping("/events")
    public ResponseEntity<ApiResponse<EventResponse>> createEvent(
            @Valid @RequestBody EventCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("이벤트 등록 완료", eventService.createEvent(request)));
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<EventResponse>>> getEvents() {
        return ResponseEntity.ok(ApiResponse.ok("이벤트 목록 조회 완료", eventService.getAdminEvents()));
    }

    // 테스트용 강제 오픈/종료
    @PatchMapping("/events/{eventId}/open")
    public ResponseEntity<ApiResponse<Void>> openEvent(@PathVariable Long eventId) {
        eventService.openEvent(eventId);
        return ResponseEntity.ok(ApiResponse.ok("이벤트 오픈 완료", null));
    }

    @PatchMapping("/events/{eventId}/close")
    public ResponseEntity<ApiResponse<Void>> closeEvent(@PathVariable Long eventId) {
        eventService.closeEvent(eventId);
        return ResponseEntity.ok(ApiResponse.ok("이벤트 종료 완료", null));
    }
}
