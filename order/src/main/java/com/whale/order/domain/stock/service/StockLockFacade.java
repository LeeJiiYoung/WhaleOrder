package com.whale.order.domain.stock.service;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class StockLockFacade {

    private final RedissonClient redissonClient;
    private final StockService stockService;

    // 재고 차감 (분산 락)
    public void deductStock(Long storeId, Long menuId, int amount) {
        RLock lock = redissonClient.getLock("stock:lock:" + storeId + ":" + menuId);
        try {
            // 최대 5초 대기, 락 획득 후 3초 안에 자동 해제
            if (!lock.tryLock(5, 3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("재고 처리 중입니다. 잠시 후 다시 시도해주세요.");
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

    // 재고 복구 (분산 락)
    public void restoreStock(Long storeId, Long menuId, int amount) {
        RLock lock = redissonClient.getLock("stock:lock:" + storeId + ":" + menuId);
        try {
            if (!lock.tryLock(5, 3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("재고 복구 처리 중입니다. 잠시 후 다시 시도해주세요.");
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
