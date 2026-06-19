# Saga 보상 트랜잭션 — Kafka DLT

> 외부 결제 또는 후속 처리 실패 시 주문/재고를 자동으로 되돌려 데이터 정합성을 유지

**관련 코드**
- `src/main/java/com/whale/order/domain/order/service/OrderProcessingService.java` — `processOrder()` / `restoreWithRetry()` / `compensate()`
- `src/main/java/com/whale/order/domain/order/service/OrderKafkaConsumer.java` — DLT 핸들러
- `src/main/java/com/whale/order/domain/order/service/OrderCancelService.java` — 시스템 자동 취소
- `src/main/java/com/whale/order/domain/payment/service/PaymentService.java` — `cancelPayment()` 환불 헬퍼
- `src/main/java/com/whale/order/domain/stock/entity/StockRestoreFailure.java`

## 실패 시나리오 4가지

| 실패 지점 | 처리 방식 |
|-----------|-----------|
| 결제 실패 (사전 차감 전) | `PaymentService.pay()` 에 `noRollbackFor = PaymentFailedException` 적용 → `Payment(FAILED)` + `PaymentHistory(FAILED)` + `Orders(CANCELLED)` + `OrderStatusHistory(CANCELLED)` 모두 commit 보존 → 시도 이력 추적 가능 |
| 재고 부족 (차감 중) | 차감된 항목만 `restoreWithRetry()` → `OrderCancelService.cancelOrder()` → 주문 CANCELLED + `PaymentService.cancelPayment()` 환불 |
| 재고 복구 실패 | `StockRestoreFailure` DB 기록 + 어드민 SSE 경고 |
| Kafka Consumer 3회 재시도 실패 | DLT 진입 → `compensate()` → `OrderCancelService.cancelOrder()` (주문 CANCELLED + 결제 환불) |

## 핵심 메커니즘

### 1. At-least-once 재전달 방어

Kafka 메시지가 중복 전달되더라도 이중 차감 방지:

```java
if (order.isStockDeducted() || order.getStatus() == OrderStatus.CANCELLED) return;
```

### 2. 부분 실패 시 부분 롤백

여러 메뉴 차감 중 일부에서 실패하면, **차감 성공한 항목만** 복구 시도:

```java
for (OrderItem item : order.getOrderItems()) {
    stockLockFacade.deductStock(...);
    deducted.add(item);  // 성공한 것만 추적
}
// 예외 발생 시
for (OrderItem item : deducted) restoreWithRetry(...);
```

### 3. 재고 복구 재시도 (200ms × 3회)

분산 락 일시 경합으로 실패할 수 있어 200ms 간격 3회 재시도:

```java
for (int attempt = 1; attempt <= 3; attempt++) {
    try {
        stockLockFacade.restoreStock(storeId, menuId, quantity);
        return;
    } catch (Exception e) {
        Thread.sleep(200);
    }
}
// 모두 실패 → StockRestoreFailure 기록 + 어드민 알림
```

### 4. DLT 보상 진입점

`OrderProcessingService.compensate(orderId)` 가 DLT Consumer에서 호출되어 `OrderCancelService.cancelOrder()` 트리거 → 주문 CANCELLED + 결제 환불.

### 5. 결제 환불 통합 — `PaymentService.cancelPayment()`

고객 자가 취소(`OrderService.cancelOrder`)와 시스템 보상(`OrderCancelService.cancelOrder`) 양쪽이 같은 헬퍼를 사용해 결제 환불 로직 중복 제거. SUCCESS 상태 결제만 통과 (PENDING/FAILED/CANCELLED 은 안전하게 skip).

## 가시화

| 신호 | 위치 |
|------|------|
| 처리 성공/실패 | Micrometer Counter `order.processed{result=success|failure}` |
| 재고 부족 | `order.stock.shortage` |
| 복구 최종 실패 | `StockRestoreFailure` 테이블 + SSE `stockRestoreFailure` 이벤트 |
| 처리 시간 | Timer `order.processing.time` |

## 한계와 다음 단계

- Choreography Saga 형태 — 명시적 SagaManager 없음. 단계가 늘어나면 Orchestrator 도입 검토
- `StockRestoreFailure` 는 기록만 함. 수동 보정 UI/CLI 미구현
- `OrderEventListener` 의 Kafka 발행 실패는 `log.error` 만 — 어드민 알림 또는 outbox 도입 검토
- 결제는 Mock — 실 결제 PG 연동 시 `PaymentHistory` 기반 멱등 처리 강화 필요
