package com.whale.order.domain.stock.service;

import com.whale.order.global.exception.StockLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockLockFacade {

    private final RedissonClient redissonClient;
    private final StockService stockService;

    // 재고 차감 (분산 락 — Redisson watchdog 자동 갱신 모드)
    public void deductStock(Long storeId, Long menuId, int amount) {
        RLock lock = redissonClient.getLock("stock:lock:" + storeId + ":" + menuId);
        try {
            // 최대 5초 대기. leaseTime 인자 생략 시 watchdog 활성 — 트랜잭션이 길어져도
            // 락이 자동 갱신되어 만료되지 않는다. 보유자 사망 시 30초 후 자동 해제.
            // (이중 방어: Stock 엔티티 @Version 으로 DB 레벨 lost update 도 차단)
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                log.warn("[분산락] 차감 락 획득 실패 storeId={} menuId={}", storeId, menuId);
                throw new StockLockException("재고 처리 중입니다. 잠시 후 다시 시도해주세요.");
            }
            stockService.deductStock(storeId, menuId, amount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재고 처리가 중단되었습니다.", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // 재고 복구 (분산 락 — Redisson watchdog 자동 갱신 모드)
    public void restoreStock(Long storeId, Long menuId, int amount) {
        //같은 매장, 메뉴에 대해서만 락걸기
        RLock lock = redissonClient.getLock("stock:lock:" + storeId + ":" + menuId);
        try {
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                log.warn("[분산락] 복구 락 획득 실패 storeId={} menuId={}", storeId, menuId);
                throw new StockLockException("재고 복구 처리 중입니다. 잠시 후 다시 시도해주세요.");
            }
            stockService.restoreStock(storeId, menuId, amount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재고 복구가 중단되었습니다.", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
