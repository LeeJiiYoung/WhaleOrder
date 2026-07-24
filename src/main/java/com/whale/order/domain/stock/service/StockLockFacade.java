package com.whale.order.domain.stock.service;

import com.whale.order.global.exception.StockLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockLockFacade {

    // Redisson 락이 새는 극단 케이스에서 발생하는 @Version 충돌을 짧게 재시도
    // (같은 분산 락을 잡은 상태에서 재시도하므로 대부분 즉시 성공)
    private static final int OPTIMISTIC_LOCK_MAX_ATTEMPTS = 3;
    private static final long OPTIMISTIC_LOCK_RETRY_DELAY_MS = 50;

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
            deductWithOptimisticRetry(storeId, menuId, amount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재고 처리가 중단되었습니다.", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void deductWithOptimisticRetry(Long storeId, Long menuId, int amount) {
        ObjectOptimisticLockingFailureException lastEx = null;
        for (int attempt = 1; attempt <= OPTIMISTIC_LOCK_MAX_ATTEMPTS; attempt++) {
            try {
                stockService.deductStock(storeId, menuId, amount);
                return;
            } catch (ObjectOptimisticLockingFailureException e) {
                lastEx = e;
                log.warn("[낙관적락] 차감 충돌 storeId={} menuId={} attempt={}/{}",
                        storeId, menuId, attempt, OPTIMISTIC_LOCK_MAX_ATTEMPTS);
                if (attempt < OPTIMISTIC_LOCK_MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(OPTIMISTIC_LOCK_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("재고 처리가 중단되었습니다.", ie);
                    }
                }
            }
        }
        log.error("[낙관적락] 차감 재시도 최종 실패 storeId={} menuId={}", storeId, menuId, lastEx);
        throw lastEx;
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
