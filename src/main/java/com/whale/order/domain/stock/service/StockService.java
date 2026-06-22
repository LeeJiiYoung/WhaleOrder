package com.whale.order.domain.stock.service;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.stock.dto.StockResponse;
import com.whale.order.domain.stock.dto.StockRestoreFailureResponse;
import com.whale.order.domain.stock.dto.StockUpdateRequest;
import com.whale.order.domain.stock.entity.Stock;
import com.whale.order.domain.stock.repository.StockRepository;
import com.whale.order.domain.stock.repository.StockRestoreFailureRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRepository stockRepository;
    private final StockRestoreFailureRepository stockRestoreFailureRepository;
    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;
    private final MemberRepository memberRepository;
    private final MeterRegistry meterRegistry;

    // 매장의 전체 메뉴 재고 목록 (재고 미설정 메뉴 포함) — 고객용, 인증/소유권 검증 없음
    @Transactional(readOnly = true)
    public List<StockResponse> getStocks(Long storeId) {
        Map<Long, Stock> stockMap = stockRepository.findByStoreIdWithMenu(storeId)
                .stream().collect(Collectors.toMap(s -> s.getMenu().getMenuId(), s -> s));

        return menuRepository.findByIsActiveTrueOrderByCreatedAtDesc().stream()
                .map(menu -> StockResponse.of(menu, stockMap.get(menu.getMenuId())))
                .toList();
    }

    // 매장의 전체 메뉴 재고 목록 (어드민용) — OWNER는 본인 매장 한정
    @Transactional(readOnly = true)
    public List<StockResponse> getStocks(Long storeId, Long callerId) {
        verifyStoreAccess(storeId, callerId);
        return getStocks(storeId);
    }

    // 특정 매장의 재고 복구 실패 목록 (OWNER는 본인 매장 한정)
    @Transactional(readOnly = true)
    public List<StockRestoreFailureResponse> getRestoreFailures(Long storeId, Long callerId) {
        verifyStoreAccess(storeId, callerId);

        return stockRestoreFailureRepository.findAllByStoreIdOrderByFailedAtDesc(storeId).stream()
                .map(StockRestoreFailureResponse::from)
                .toList();
    }

    // 전체 매장의 재고 복구 실패 목록 (ADMIN 전용 — SecurityConfig에서 이미 ADMIN으로 제한됨)
    @Transactional(readOnly = true)
    public List<StockRestoreFailureResponse> getRestoreFailures() {
        return stockRestoreFailureRepository.findAllByOrderByFailedAtDesc().stream()
                .map(StockRestoreFailureResponse::from)
                .toList();
    }

    // OWNER는 본인 소유 매장만 접근 가능, ADMIN은 제한 없음
    private void verifyStoreAccess(Long storeId, Long callerId) {
        Member caller = memberRepository.findById(callerId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다"));
        if (caller.getRole() != MemberRole.OWNER) {
            return;
        }
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다"));
        if (!store.getOwner().getMemberId().equals(callerId)) {
            throw new IllegalArgumentException("본인 매장만 접근할 수 있습니다");
        }
    }

    // 재고 차감 — StockLockFacade 가 Redis 락을 잡은 상태에서 호출.
    // timeout=10 으로 트랜잭션이 hang 되어도 10초 안에 강제 종료 → finally unlock 까지 빠르게 도달.
    // watchdog 자동 갱신 모드와 결합해 락이 무한정 보유되는 상황도 방지.
    @Transactional(timeout = 10)
    public void deductStock(Long storeId, Long menuId, int amount) {
        try {
            Stock stock = stockRepository.findByStoreAndMenu(storeId, menuId)
                    .orElseThrow(() -> new IllegalStateException("재고가 설정되지 않은 메뉴입니다 (menuId=" + menuId + ")"));
            int before = stock.getQuantity();
            stock.deduct(amount);
            log.info("[재고차감] storeId={} menuId={} {}개 차감 ({}→{})",
                    storeId, menuId, amount, before, before - amount);
        } catch (IllegalStateException e) {
            log.warn("[재고부족] storeId={} menuId={} 요청={}개 error={}", storeId, menuId, amount, e.getMessage());
            Counter.builder("stock.shortage.total")
                    .tag("storeId", String.valueOf(storeId))
                    .tag("menuId", String.valueOf(menuId))
                    .description("재고 부족으로 주문 실패 횟수")
                    .register(meterRegistry)
                    .increment();
            throw e;
        }
    }

    // 주문 취소 시 재고 복구 — StockLockFacade 가 Redis 락을 잡은 상태에서 호출
    @Transactional(timeout = 10)
    public void restoreStock(Long storeId, Long menuId, int amount) {
        stockRepository.findByStoreAndMenu(storeId, menuId).ifPresent(stock -> {
            int before = stock.getQuantity();
            stock.restore(amount);
            log.info("[재고복구] storeId={} menuId={} {}개 복구 ({}→{})",
                    storeId, menuId, amount, before, before + amount);
        });
    }

    // 특정 메뉴 재고 설정 (없으면 생성, 있으면 수정)
    @Transactional
    public StockResponse setStock(Long storeId, Long menuId, StockUpdateRequest request, Long callerId) {
        verifyStoreAccess(storeId, callerId);

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다"));
        Menu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 메뉴입니다"));

        Stock stock = stockRepository.findByStore_StoreIdAndMenu_MenuId(storeId, menuId)
                .orElseGet(() -> stockRepository.save(
                        Stock.builder().store(store).menu(menu).quantity(request.resolvedQuantity()).build()));

        stock.updateQuantity(request.resolvedQuantity());
        return StockResponse.of(menu, stock);
    }
}
