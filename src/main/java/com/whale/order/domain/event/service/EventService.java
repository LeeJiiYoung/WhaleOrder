package com.whale.order.domain.event.service;

import com.whale.order.domain.event.dto.*;
import com.whale.order.domain.event.entity.Event;
import com.whale.order.domain.event.entity.Goods;
import com.whale.order.domain.event.repository.EventRepository;
import com.whale.order.domain.event.repository.GoodsRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.global.storage.ImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final GoodsRepository goodsRepository;
    private final StoreRepository storeRepository;
    private final ImageStorageService imageStorageService;

    // ─── 고객용 ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<EventResponse> getActiveEvents() {
        return eventRepository.findActiveEvents().stream()
                .map(EventResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다: " + eventId));
        return EventResponse.from(event);
    }

    // ─── 관리자용 ─────────────────────────────────────────────────

    @Transactional
    public GoodsResponse createGoods(GoodsCreateRequest request) {
        Store store = storeRepository.findById(request.getStoreId())
                .orElseThrow(() -> new IllegalArgumentException("매장을 찾을 수 없습니다"));

        String imageUrl = (request.getImageFile() != null && !request.getImageFile().isEmpty())
                ? imageStorageService.store(request.getImageFile())
                : null;

        Goods goods = goodsRepository.save(Goods.builder()
                .store(store)
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .imageUrl(imageUrl)
                .build());

        return GoodsResponse.from(goods);
    }

    @Transactional(readOnly = true)
    public List<GoodsResponse> getGoods() {
        return goodsRepository.findAllWithStore().stream()
                .map(GoodsResponse::from)
                .toList();
    }

    @Transactional
    public EventResponse createEvent(EventCreateRequest request) {
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

        return EventResponse.from(event);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getAdminEvents() {
        return eventRepository.findAllWithGoods().stream()
                .map(EventResponse::from)
                .toList();
    }

    @Transactional
    public void openEvent(Long eventId) {
        eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다"))
                .open();
    }

    @Transactional
    public void closeEvent(Long eventId) {
        eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다"))
                .close();
    }
}
