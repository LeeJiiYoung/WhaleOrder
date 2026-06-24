# 동시성 제어 — Redisson 분산 락

> 수천 명이 동시에 같은 메뉴를 주문해도 재고가 음수로 떨어지지 않도록 매장+메뉴 단위로 직렬화

**관련 코드**
- `src/main/java/com/whale/order/domain/stock/service/StockLockFacade.java`
- `src/main/java/com/whale/order/domain/stock/service/StockService.java`
- `src/main/java/com/whale/order/domain/stock/entity/Stock.java` (`@Version` 낙관적 락)
- `src/main/java/com/whale/order/global/config/RedissonConfig.java`
- `src/main/java/com/whale/order/global/exception/StockLockException.java`

## 문제

```
스레드 A, B 동시 진입 → 둘 다 재고=1 조회 → 둘 다 차감 → 재고 -1 (오버셀)
```

## 해결 — 3중 방어

매장+메뉴 단위 락 키로 락 범위를 좁혀 같은 매장의 다른 메뉴 주문은 블로킹하지 않음.

| 항목          | 값                               | 이유                  |
| ----------- | ------------------------------- | ------------------- |
| 락 키         | `stock:lock:{storeId}:{menuId}` | 매장·메뉴 단위 분리로 처리량 확보 |
| `waitTime`  | 5초                              | 사용자 응답 지연 허용 한계     |
| `leaseTime` | 생략 (watchdog)                   | Redisson 자동 갱신 — 트랜잭션 길이에 무관하게 보유. 보유자 사망 시 30초 후 자동 해제 |
| 해제 가드       | `isHeldByCurrentThread()`       | 타임아웃 후 unlock 예외 방지 |
| Tx timeout  | `@Transactional(timeout = 10)`  | hang 발생 시 10초 안에 강제 종료 → finally unlock 빠른 도달 |
| Stock `@Version` | Hibernate 낙관적 락         | watchdog 도 새는 극단 케이스(Redis 장애 등)에 DB 레벨 lost update 차단 |

### 3중 방어 동작 그림

```
[1차] 분산 락 (Redisson watchdog)
       ├ 같은 (storeId, menuId) 동시 진입을 직렬화
       └ watchdog 자동 갱신 → leaseTime 게임 없음
              ↓
[2차] @Transactional(timeout = 10)
       └ 트랜잭션 hang 시 강제 종료 → 락 보유자가 절대 무한 hang 안 됨
              ↓
[3차] Stock @Version (낙관적 락)
       └ 어떤 이유로 두 트랜잭션이 동시 진입해도 UPDATE WHERE version=? 로 한쪽만 성공
       └ 다른 쪽은 OptimisticLockException → OrderProcessingService 의 Saga 보상이 받음
```

## 핵심 스니펫

```java
RLock lock = redissonClient.getLock("stock:lock:" + storeId + ":" + menuId);
try {
    // leaseTime 인자 생략 → watchdog 활성
    if (!lock.tryLock(5, TimeUnit.SECONDS)) {
        throw new StockLockException("재고 처리 중입니다. 잠시 후 다시 시도해주세요.");
    }
    stockService.deductStock(storeId, menuId, amount);   // @Transactional(timeout = 10)
} finally {
    if (lock.isHeldByCurrentThread()) lock.unlock();
}
```

`StockLockFacade.deductStock()` / `restoreStock()` 모두 동일 패턴.

## 검증

- 단위: `src/test/java/.../StockLockFacadeTest.java` (있는 경우)
- 통합/부하: `k6/stock-concurrency-test.js` — 20명 동시 주문 / 재고 10개 → 성공 10건만 통과

## 한계와 다음 단계

- Redis 단일 노드 장애 시 락 유실 가능 → `@Version` 으로 DB 레벨 2차 방어 동작. 추가로 Redisson Red Lock 알고리즘 도입 검토
- `OptimisticLockException` 발생 시 자동 retry 는 현재 없음 — `OrderProcessingService` 의 Saga 보상이 받아냄. 향후 retry 1–2 회 정도 추가 검토
