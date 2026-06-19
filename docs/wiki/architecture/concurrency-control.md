# 동시성 제어 — Redisson 분산 락

> 수천 명이 동시에 같은 메뉴를 주문해도 재고가 음수로 떨어지지 않도록 매장+메뉴 단위로 직렬화

**관련 코드**
- `src/main/java/com/whale/order/domain/stock/service/StockLockFacade.java`
- `src/main/java/com/whale/order/global/config/RedissonConfig.java`
- `src/main/java/com/whale/order/global/exception/StockLockException.java`

## 문제

```
스레드 A, B 동시 진입 → 둘 다 재고=1 조회 → 둘 다 차감 → 재고 -1 (오버셀)
```

## 해결

매장+메뉴 단위 락 키로 락 범위를 좁혀 같은 매장의 다른 메뉴 주문은 블로킹하지 않음.

| 항목          | 값                               | 이유                  |
| ----------- | ------------------------------- | ------------------- |
| 락 키         | `stock:lock:{storeId}:{menuId}` | 매장·메뉴 단위 분리로 처리량 확보 |
| `waitTime`  | 5초                              | 사용자 응답 지연 허용 한계     |
| `leaseTime` | 3초                              | 데드락 방지 (자동 해제)      |
| 해제 가드       | `isHeldByCurrentThread()`       | 타임아웃 후 unlock 예외 방지 |

## 핵심 스니펫

```java
RLock lock = redissonClient.getLock("stock:lock:" + storeId + ":" + menuId);
try {
    if (!lock.tryLock(5, 3, TimeUnit.SECONDS)) {
        throw new StockLockException("재고 처리 중입니다. 잠시 후 다시 시도해주세요.");
    }
    stockService.deductStock(storeId, menuId, amount);
} finally {
    if (lock.isHeldByCurrentThread()) lock.unlock();
}
```

`StockLockFacade.deductStock()` / `restoreStock()` 모두 동일 패턴.

## 검증

- 단위: `src/test/java/.../StockLockFacadeTest.java` (있는 경우)
- 통합/부하: `k6/stock-concurrency-test.js` — 20명 동시 주문 / 재고 10개 → 성공 10건만 통과

## 한계와 다음 단계

- Redis 단일 노드 장애 시 락 유실 가능 → Redisson Red Lock 알고리즘 도입 검토
- 락 점유 시간(3초) 초과 시 후속 처리 보장 안 됨 → 트랜잭션 시간 모니터링 필요
