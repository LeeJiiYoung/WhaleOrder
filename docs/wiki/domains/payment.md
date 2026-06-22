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

## 표시 금액 확인 (Amount Confirmation)

클라이언트가 결제 화면에서 본 금액(`PaymentRequest.expectedAmount`)을 서버의 cart 합계와 대조해 **가격 변동 · 다른 탭 카트 동시 수정 · 중간자 변조**를 차단한다.

- **실제 청구액은 항상 서버 cart 기준** — 클라이언트가 보낸 값은 비교용일 뿐, PG에 전달되는 금액은 아님
- 불일치 시 `IllegalStateException("표시된 금액과 실제 금액이 다릅니다. 장바구니를 새로고침해주세요.")`
- WARN 로그로 시도 횟수 모니터링 가능
- `expectedAmount` 는 `@NotNull + @PositiveOrZero` — 프런트(`PaymentPage.jsx`)에서 cart total 을 항상 함께 전송
- Service 레이어에는 여전히 null guard 가 남아 있음 (어노테이션 검증은 컨트롤러 `@Valid` 에서만 자동 실행 → service 직접 호출 대비 defensive)

## 핵심 플로우 — 선결제

```
POST /api/payments
   ▼
PaymentService.pay()  @Transactional
   ├─ 1. 장바구니 조회
   ├─ 2. 표시 금액 확인 (expectedAmount vs cart.totalPrice — null 이면 skip)
   ├─ 3. 매장 OPEN / 메뉴 isOnSale / Stock 존재 검증
   ├─ 4. SHA-256 멱등성 키 (memberId : storeId : method : orderType : cart) — Redis SET NX EX
   │     ├─ getResult(key) 캐시 hit  → 반환
   │     └─ markProcessing(key) false → DuplicateRequestException
   ├─ 5. Orders + Payment(PENDING) 저장 + PaymentHistory(PENDING)
   ├─ 6. Mock 결제 판정 (90% 성공)
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

## 도메인 invariant (음수 + overflow 차단)

| 위치 | 규칙 | 방어 단계 |
|------|------|----------|
| `Payment.amount` | `Long` / DB `BIGINT` | 타입 |
| `Payment` 생성자 | `amount >= 0` | 코드 |
| `payment` 테이블 | `CHECK (amount >= 0)` | DB |
| `PaymentRequest.expectedAmount` | `@NotNull + @PositiveOrZero` (`Long`) | 컨트롤러 `@Valid` |

## 관련 문서

- [Order 도메인](order.md) — 주문 생성/취소 흐름 전체
- [Saga 보상 트랜잭션](../architecture/saga-compensation.md)
- 학습 노트: [Mock 결제 & Saga 패턴 (2026-05-27)](../notes/개념정리_20260527.md)
