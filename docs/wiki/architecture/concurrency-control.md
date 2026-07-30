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
| `waitTime`  | 5초                              | Kafka 모드에선 Consumer 처리 지연 한계 (사용자는 이미 결제 응답을 받은 뒤). 동기 폴백 모드에선 그대로 결제 응답 지연이 된다 |
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

## 낙관적 락 충돌 시 사용자 알림 & 재시도

`Stock.@Version` 충돌은 `StockLockFacade.deductStock()` 안에서 **최대 3회 (50ms 간격) 자동 재시도** 됨 (같은 Redisson 락을 잡은 상태이므로 대부분 즉시 성공). 재시도까지 모두 실패하면 `ObjectOptimisticLockingFailureException` 이 `OrderProcessingService.processOrder()` 로 올라오고, 예외 종류별로 분기된 사용자 문구가 SSE 로 push 됨:

| 예외 | 사용자 메시지 (SSE `message` 필드) | 로그 reason 태그 |
|------|-----------------------------|----------------|
| `IllegalStateException` (재고 부족) | `Stock.deduct()` 메시지 원문 (`"아메리카노 재고가 부족합니다 (남은 재고: 0개)"`) | `stock_shortage` |
| `ObjectOptimisticLockingFailureException` | `"주문이 몰려 처리에 실패했습니다. 잠시 후 다시 시도해주세요."` | `lock_conflict` |
| `StockLockException` (분산 락 획득 실패) | 즉시 알림 없음 — Kafka 재시도 위임. 3회 소진 시 DLT `compensate()` 가 `"주문 처리에 실패했습니다. 잠시 후 다시 시도해주세요."` push | `lock_timeout` (result=retry) |
| 기타 `Exception` | `"주문 처리 중 오류가 발생했습니다."` | `error` |

Hibernate raw 메시지 (`"Row was updated or deleted by another transaction : [com.whale.order.domain.stock.entity.Stock#57]"`) 는 더 이상 사용자에게 노출되지 않음.

메트릭도 태그가 분리됨: `order.processing.time{result=failure|retry, reason=<사유>}`, `order.stock.shortage` 는 실제 재고 부족일 때만 증가.

### StockLockException 재시도 흐름

분산 락 `tryLock(5s)` 조차 실패하는 것은 다른 스레드가 오래 홀드 중이라는 뜻이므로 **일시적** 상태로 간주 (`StockLockException.java:4` 주석 의도). `OrderProcessingService.processOrder()` 는 이 예외에 대해:

1. 부분 차감된 항목이 있다면 `restoreWithRetry()` 로 먼저 복구 (재전달로 인한 이중 차감 방지)
2. 예외 rethrow → `OrderKafkaConsumer.consume()` 이 재던짐 → offset 미커밋 → 메시지 재전달
3. Kafka 3회 재시도 소진 시 `order-created.DLT` 로 이동 → `OrderProcessingService.compensate()` 진입 → 주문 취소 + 결제 환불 + SSE `FAILED` push

`Orders.isStockDeducted` 플래그는 전체 아이템 차감 완료 시점에만 세팅되므로, 재시도가 처음부터 다시 돌아도 안전함.

## 한계와 다음 단계

- Redis 단일 노드 장애 시 락 유실 가능 → `@Version` 으로 DB 레벨 2차 방어 동작. 추가로 Redisson Red Lock 알고리즘 도입 검토
