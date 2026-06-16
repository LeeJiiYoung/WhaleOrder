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

/**
 * 굿즈(한정판 상품) 및 한정 판매 이벤트의 등록/조회를 담당하는 서비스.
 * 대기열·구매 처리는 각각 {@link EventQueueService}, {@link EventPurchaseService}가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final GoodsRepository goodsRepository;
    private final StoreRepository storeRepository;
    private final ImageStorageService imageStorageService;

    // ─── 고객용 ───────────────────────────────────────────────────

    /**
     * 고객에게 노출할 활성 이벤트 목록을 조회한다 (OPEN + SCHEDULED 상태).
     */
    @Transactional(readOnly = true)
    public List<EventResponse> getActiveEvents() {
        return eventRepository.findActiveEvents().stream()
                .map(EventResponse::from)
                .toList();
    }

    /**
     * 이벤트 상세 정보를 조회한다.
     */
    @Transactional(readOnly = true)
    public EventResponse getEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다: " + eventId));
        return EventResponse.from(event);
    }

    // ─── 관리자용 ─────────────────────────────────────────────────

    /**
     * 굿즈를 등록한다. 이미지 파일이 첨부되면 저장 후 URL을 함께 기록한다.
     */
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

    /**
     * 등록된 굿즈 목록을 조회한다.
     */
    @Transactional(readOnly = true)
    public List<GoodsResponse> getGoods() {
        return goodsRepository.findAllWithStore().stream()
                .map(GoodsResponse::from)
                .toList();
    }

    /**
     * 한정 판매 이벤트를 등록한다. 초기 상태는 SCHEDULED이며 EventScheduler가 openAt 시각에 자동 오픈한다.
     */
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

    /**
     * 어드민 관점의 전체 이벤트 목록을 조회한다 (상태 무관, 굿즈 정보 포함).
     */
    @Transactional(readOnly = true)
    public List<EventResponse> getAdminEvents() {
        return eventRepository.findAllWithGoods().stream()
                .map(EventResponse::from)
                .toList();
    }

    /**
     * 이벤트를 강제로 OPEN 상태로 전환한다 (테스트용. 정상 흐름은 EventScheduler가 openAt 시각에 자동 처리).
     */
    @Transactional
    public void openEvent(Long eventId) {
        eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다"))
                .open();
    }

    /**
     * 이벤트를 강제로 CLOSED 상태로 전환한다 (테스트용).
     */
    @Transactional
    public void closeEvent(Long eventId) {
        eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다"))
                .close();
    }
}
