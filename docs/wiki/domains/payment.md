# Payment — 결제

> Mock 결제(성공 90% / 실패 10%) + 선결제 흐름의 진입점. 주문 생성도 여기서 함께 처리.

**디렉토리**: `src/main/java/com/whale/order/domain/payment/`

## 구성

| 분류 | 파일 |
|------|------|
| Entity | `Payment`, `PaymentHistory`, `PaymentMethod`, `PaymentStatus` |
| Controller | `PaymentController` (`POST /api/payments`, `GET /api/payments/orders/{orderId}`) |
| Service | `PaymentService` (`pay`, `cancelPayment`, `getPaymentByOrder`) |
| Repository | `PaymentRepository`, `PaymentHistoryRepository` |

## Mock 결제

- 외부 PG 없이 `ThreadLocalRandom` 확률(90% 성공)로 시뮬레이션
- 성공 시 `MOCK-{UUID}` 트랜잭션 ID 발급
- 실패 시 `PaymentFailedException` → `@Transactional(noRollbackFor = PaymentFailedException)` 덕에 시도 이력(`Payment.FAILED`, `PaymentHistory(FAILED)`, `Orders.CANCELLED`)은 commit 으로 보존

## 핵심 플로우 — 선결제

```
POST /api/payments
   ▼
PaymentService.pay()  @Transactional
   ├─ 1. 장바구니 조회
   ├─ 2. 매장 OPEN / 메뉴 isOnSale / Stock 존재 검증
   ├─ 3. SHA-256 멱등성 키 (memberId : storeId : method : orderType : cart)
   │     ├─ getResult(key) 캐시 hit  → 반환
   │     └─ markProcessing(key) false → DuplicateRequestException
   ├─ 4. Orders + Payment(PENDING) 저장 + PaymentHistory(PENDING)
   ├─ 5. Mock 결제 판정 (90% 성공)
   │
   ├─ 성공 → Payment SUCCESS + PaymentHistory(SUCCESS)
   │          + eventPublisher.publishEvent(OrderCreatedEvent)
   │          + idempotencyService.saveResult(key, response)
   │
   └─ 실패 → Payment FAILED + PaymentHistory(FAILED) + order.cancel() + History(CANCELLED)
              + throw PaymentFailedException
              └─ @Transactional(noRollbackFor) 로 outer 는 commit (시도 이력 보존)
              └─ catch 에서 idempotencyService.delete(key) → 재시도 허용

━ outer commit ━

OrderEventListener.onOrderCreated  (AFTER_COMMIT)
   ├─ Kafka publish (order-created)
   └─ cartService.clearCart(memberId)
```

## 결제 환불 — `cancelPayment(order, reason)`

고객 자가 취소(`OrderService.cancelOrder`)와 시스템 보상(`OrderCancelService.cancelOrder`) 양쪽이 재사용하는 공통 헬퍼.

```java
@Transactional
public void cancelPayment(Orders order, String reason) {
    paymentRepository.findByOrders(order)
        .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)  // 안전 가드
        .ifPresent(payment -> {
            payment.cancel(reason);            // Payment.cancel()이 다시 SUCCESS 검증
            paymentRepository.save(payment);
            paymentHistoryRepository.save(... CANCELLED ...);
        });
}
```

- `SUCCESS` 만 통과 — PENDING/FAILED/CANCELLED 결제는 안전하게 skip
- `Payment.cancel()` 자체도 SUCCESS 가 아니면 `IllegalStateException` — 이중 가드

## 관련 문서

- [Order 도메인](order.md) — 주문 생성/취소 흐름 전체
- [Saga 보상 트랜잭션](../architecture/saga-compensation.md)
- 학습 노트: [Mock 결제 & Saga 패턴 (2026-05-27)](../notes/개념정리_20260527.md)
