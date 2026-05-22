package com.whale.order.domain.event.controller;

import com.whale.order.domain.event.dto.*;
import com.whale.order.domain.event.entity.Event;
import com.whale.order.domain.event.entity.Goods;
import com.whale.order.domain.event.repository.EventRepository;
import com.whale.order.domain.event.repository.GoodsRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminEventController {

    private final EventRepository eventRepository;
    private final GoodsRepository goodsRepository;
    private final StoreRepository storeRepository;

    // ── 굿즈 ────────────────────────────────────────────────────────

    @PostMapping("/goods")
    public ResponseEntity<ApiResponse<GoodsResponse>> createGoods(
            @Valid @RequestBody GoodsCreateRequest request) {

        Store store = storeRepository.findById(request.storeId())
                .orElseThrow(() -> new IllegalArgumentException("매장을 찾을 수 없습니다"));

        Goods goods = goodsRepository.save(Goods.builder()
                .store(store)
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .imageUrl(request.imageUrl())
                .build());

        return ResponseEntity.ok(ApiResponse.ok("굿즈 등록 완료", GoodsResponse.from(goods)));
    }

    @GetMapping("/goods")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<GoodsResponse>>> getGoods() {
        List<GoodsResponse> goods = goodsRepository.findAllWithStore()
                .stream().map(GoodsResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok("굿즈 목록 조회 완료", goods));
    }

    // ── 이벤트 ──────────────────────────────────────────────────────

    @PostMapping("/events")
    public ResponseEntity<ApiResponse<EventResponse>> createEvent(
            @Valid @RequestBody EventCreateRequest request) {

        Store store = storeRepository.findById(request.storeId())
                .orElseThrow(() -> new IllegalArgumentException("매장을 찾을 수 없습니다"));
        Goods goods = goodsRepository.findById(request.goodsId())
                .orElseThrow(() -> new IllegalArgumentException("굿즈를 찾을 수 없습니다"));

        Event event = eventRepository.save(Event.builder()
                .store(store)
                .goods(goods)
                .name(request.name())
                .openAt(request.openAt())
                .capacity(request.capacity())
                .perPersonLimit(request.perPersonLimit())
                .build());

        return ResponseEntity.ok(ApiResponse.ok("이벤트 등록 완료", EventResponse.from(event)));
    }

    @GetMapping("/events")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<EventResponse>>> getEvents() {
        List<EventResponse> events = eventRepository
                .findAllWithGoods()
                .stream().map(EventResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.ok("이벤트 목록 조회 완료", events));
    }

    // 테스트용 강제 오픈/종료
    @PatchMapping("/events/{eventId}/open")
    public ResponseEntity<ApiResponse<Void>> openEvent(@PathVariable Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다"));
        event.open();
        eventRepository.save(event);
        return ResponseEntity.ok(ApiResponse.ok("이벤트 오픈 완료", null));
    }

    @PatchMapping("/events/{eventId}/close")
    public ResponseEntity<ApiResponse<Void>> closeEvent(@PathVariable Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다"));
        event.close();
        eventRepository.save(event);
        return ResponseEntity.ok(ApiResponse.ok("이벤트 종료 완료", null));
    }
}
