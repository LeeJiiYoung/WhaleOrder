# Payment — 결제

> 토스페이먼츠 결제위젯(v2) 연동. `prepare`(주문 임시 저장) → 결제창 → `confirm`(승인 확정) 2단계 흐름이며, 주문 생성은 `prepare` 시점에 함께 처리된다.

**디렉토리**: `src/main/java/com/whale/order/domain/payment/`

## 구성

| 분류 | 파일 |
|------|------|
| Entity | `Payment`, `PaymentHistory`, `PaymentMethod`, `PaymentStatus` |
| Controller | `PaymentController` (`POST /api/payments/prepare`, `POST /api/payments/confirm`, `POST /api/payments/cancel-pending`, `GET /api/payments/orders/{orderId}`) |
| Service | `PaymentService` (`prepare`, `confirm`, `cancelPayment`, `cancelAwaitingPayment`, `cancelAwaitingPaymentSystem`, `getPaymentByOrder`), `PaymentSweepScheduler` |
| 외부 연동 | `TossPaymentClient` (토스 `/v1/payments/confirm`, `/v1/payments/{paymentKey}/cancel` 호출) |
| Repository | `PaymentRepository`, `PaymentHistoryRepository` |

## 토스 orderId 변환

토스가 요구하는 `orderId`는 영문/숫자/`-`/`_` 로 이루어진 6~64자 문자열이다. 우리 내부 PK(`Long`, 1~2자리도 흔함)를 그대로 못 쓰므로, `prepare` 응답에서만 `"whale-" + 내부 orderId`(`TOSS_ORDER_ID_PREFIX`) 형태의 토스 전용 문자열을 만들어 내려준다. DB에는 여전히 내부 PK만 저장하며, `confirm` 요청으로 돌아온 토스 orderId는 `parseTossOrderId()`가 접두사를 검증·제거해 내부 PK로 되돌린다 — 접두사가 없거나 뒷부분이 숫자가 아니면 위조된 값으로 보고 거부한다.

## 표시 금액 확인 (Amount Confirmation)

클라이언트가 결제 화면에서 본 금액(`PaymentPrepareRequest.expectedAmount`)을 서버의 cart 합계와 대조해 **가격 변동 · 다른 탭 카트 동시 수정 · 중간자 변조**를 차단한다. 검증은 두 시점에서 이뤄진다.

- **prepare**: `expectedAmount` vs `cart.totalPrice()` — 불일치 시 `IllegalStateException`
- **confirm**: successUrl 리다이렉트로 돌아온 `amount` vs `prepare`에서 저장해둔 `payment.getAmount()` — 리다이렉트 과정에서 금액이 조작되지 않았는지 재확인. 불일치 시 `IllegalStateException("결제 금액이 일치하지 않습니다")`
- **실제 청구액은 항상 토스 결제창에 전달된 금액**이며, 토스 승인 API 자체도 금액을 재검증한다

## 핵심 플로우 — prepare → 결제창 → confirm

```
POST /api/payments/prepare   @Transactional
   ▼
PaymentService.prepare()
   ├─ 1. 장바구니 조회 + 표시 금액 확인
   ├─ 2. 매장 OPEN / 메뉴 isOnSale / Stock 존재 검증
   ├─ 3. SHA-256 멱등성 키 (memberId : storeId : orderType : cart) — Redis SET NX EX
   ├─ 4. Orders(AWAITING_PAYMENT) + Payment(PENDING, method 없음) 저장 + PaymentHistory(PENDING)
   └─ 5. { orderId, tossOrderId("whale-{id}"), amount, orderName } 반환
        └─ idempotencyService.saveResult(key, response)

━ 프런트: 토스 결제위젯(widgets.requestPayment) 오픈 → 사용자 결제 ━
━ 토스가 successUrl로 브라우저 리다이렉트 (paymentKey·orderId·amount 쿼리 파라미터) ━

POST /api/payments/confirm   @Transactional(noRollbackFor = PaymentFailedException)
   ▼
PaymentService.confirm()
   ├─ 1. tossOrderId → 내부 orderId 역변환 (parseTossOrderId)
   ├─ 2. confirm 전용 멱등성 락 ("confirm:" + orderId, Redis SET NX EX)
   │     └─ StrictMode 이중 호출·네트워크 재시도·새로고침 등으로 동시에 들어와도 1회만 처리
   ├─ 3. 이미 SUCCESS면 토스 재승인 없이 캐시된 결과 반환 (멱등)
   ├─ 4. 표시 금액 재검증 (payment.amount vs request.amount)
   ├─ 5. TossPaymentClient.confirm() → 토스 실제 승인 API 호출 (Basic Auth)
   │
   ├─ 성공 → order.confirmPayment() (AWAITING_PAYMENT → PENDING) + Payment SUCCESS(paymentKey, method)
   │          + PaymentHistory(SUCCESS) + eventPublisher.publishEvent(OrderCreatedEvent)
   │          + kafkaOutboxService.enqueue("order-created", ...)
   │
   └─ 실패(토스 4xx/5xx) → Payment FAILED + PaymentHistory(FAILED) + order.cancelUnpaid() + History(CANCELLED)
              + throw PaymentFailedException
              └─ noRollbackFor 로 outer는 commit (시도 이력 보존)
              └─ catch에서 idempotencyService.delete(key) → 재시도 허용

━ outer commit ━

OrderEventListener.onOrderCreated  (AFTER_COMMIT)
   ├─ Kafka publish (order-created)
   └─ cartService.clearCart(memberId)
```

## 결제 대기(AWAITING_PAYMENT) 정리

`prepare`는 실제 결제가 이뤄지기 **전에** 주문을 만든다 — 토스 결제창을 열려면 orderId가 먼저 있어야 하기 때문. 그래서 주문은 `AWAITING_PAYMENT`(결제 대기)로 시작해 `confirm` 성공 시에만 `PENDING`(접수 대기)으로 넘어간다. 이 둘을 분리하지 않고 처음부터 `PENDING`으로 만들면(과거 Mock PG `pay()` 시절 설계) "매장에 접수됐다"는 뜻의 `PENDING`이 "결제도 안 끝났다"는 상태까지 뭉뚱그리게 되어, 결제창에서 취소·거절되거나 사용자가 그냥 이탈해도 주문이 실제로 접수된 것처럼 어드민 큐·고객 주문 목록에 남는 문제가 있었다.

confirm까지 가지 못하고 끝나는 경로는 세 가지이고, 정리 방식도 다르다.

| 경로 | 트리거 | 정리 방법 |
|------|--------|----------|
| 결제창 자체 취소/거절 (약관 미동의, 창 닫기 등) | `widgets.requestPayment()`가 프런트에서 reject | 정리 안 함(의도적) — 사용자가 같은 화면에서 바로 재시도 가능해야 하므로 |
| 토스가 결제 거절 → `/fail` 리다이렉트 | `PaymentFailPage` 진입 | `POST /api/payments/cancel-pending` → `PaymentService.cancelAwaitingPayment()` |
| 사용자가 결제창 도중 탭을 닫거나 이탈 | (콜백 없음) | `PaymentSweepScheduler`가 5분마다 30분 이상 `AWAITING_PAYMENT`인 주문을 정리 |

```java
private void doCancelAwaitingPayment(Orders order, String reason) {
    if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) return;  // 이미 confirm 성공했거나 이미 정리됨 — 무시

    paymentRepository.findByOrders(order).ifPresent(payment -> {
        payment.fail(reason);
        savePaymentHistory(payment, PaymentStatus.FAILED, reason);
    });
    order.cancelUnpaid();                       // AWAITING_PAYMENT 상태에서만 허용
    orderHistoryRepository.save(... CANCELLED ...);

    // prepare()의 SHA-256 멱등성 캐시도 함께 무효화 — 안 지우면 카트가 그대로인 채 60초 안에
    // 재시도할 때 이 (이제 취소된) orderId를 그대로 돌려줘버린다.
    idempotencyService.delete(generatePrepareIdempotencyKey(...));
}
```

- `cancelAwaitingPayment(memberId, tossOrderId, reason)` — 고객 요청 경로. 소유자 검증 포함
- `cancelAwaitingPaymentSystem(orderId, reason)` — 스케줄러 전용. 소유자 검증 없이 orderId만으로 정리
- 둘 다 `confirm`과 같은 Redis 멱등성 락 키(`"confirm:" + orderId`)를 공유 — `confirm`이 마침 진행 중이면 락 선점에 실패해 조용히 아무 것도 안 하고 반환한다. 실제로 결제가 확정되는 주문을 여기서 잘못 취소하는 사고를 막기 위함
- 이미 `AWAITING_PAYMENT`가 아니면(이미 성공했거나 이미 정리됐으면) 그대로 무시 — 몇 번을 호출해도 안전(멱등)
- 관리자 주문 목록(`GET /api/admin/orders`)과 고객 "내 주문" 목록 둘 다 필터 미지정 시 `AWAITING_PAYMENT`를 기본 제외한다 — 결제가 확정되지 않은 임시 주문은 매장 입장에서도, 고객 입장에서도 아직 "존재하는 주문"이 아니기 때문

## 토스 API 통신 로깅

`TossPaymentClient`는 `RestClient` + `ClientHttpRequestInterceptor`로 토스와 주고받은 모든 요청/응답(메서드·URI·body·status)을 `[토스API 요청]`/`[토스API 응답]` 로그로 남긴다. 응답 스트림을 인터셉터와 실제 파싱 양쪽에서 읽어야 하므로 `BufferingClientHttpRequestFactory`로 감싸 스트림을 재사용 가능하게 했다.

## 결제 환불(취소) — `cancelPayment(order, reason)`

고객 자가 취소(`OrderService.cancelOrder`)와 시스템 보상(`OrderCancelService.cancelOrder`) 양쪽이 재사용하는 공통 헬퍼.

```java
@Transactional
public void cancelPayment(Orders order, String reason) {
    paymentRepository.findByOrders(order)
        .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)  // 안전 가드
        .ifPresent(payment -> {
            TossCancelResponse tossResponse =
                tossPaymentClient.cancel(payment.getExternalTxId(), reason);  // 토스 취소 API 먼저 호출
            // 토스가 거절하면 PaymentFailedException을 던져 호출부 트랜잭션 자체를 롤백시킨다.

            payment.cancel(reason);
            paymentRepository.save(payment);
            paymentHistoryRepository.save(... CANCELLED ...);
        });
}
```

- `SUCCESS` 만 통과 — PENDING/FAILED/CANCELLED 결제는 안전하게 skip
- **토스 취소 API를 먼저 호출해 실제로 승인받은 뒤에만** 우리 DB도 `CANCELLED`로 바꾼다
- 토스가 취소를 거절하면 예외를 그대로 던져 호출부(주문 취소·재고 복구 포함)의 트랜잭션 전체를 롤백시킨다 — `prepare`/`confirm`과 달리 `noRollbackFor`를 쓰지 않는다. "환불 실패 이력을 남기고 주문은 취소된 채로 두는" 대신, 돈이 안 돌아왔으면 주문 취소 자체도 없었던 일로 되돌리는 정책
- 이 정책은 고객 자가 취소·관리자 취소·재고 부족으로 인한 시스템 자동 취소(Kafka `OrderProcessingService`/`OrderCancelService`) 세 경로 모두에 동일하게 적용된다 — `cancelPayment()`가 공통으로 재사용되기 때문
- `Payment.cancel()` 자체도 SUCCESS가 아니면 `IllegalStateException` — 이중 가드
- `externalTxId`가 `"MOCK-"`로 시작하는 레거시 테스트 데이터는 토스 취소를 건너뛴다 (과거 Mock PG 시절 데이터 방어용 가드, 지금은 새로 발생하지 않음)

## 도메인 invariant (음수 + overflow 차단)

| 위치 | 규칙 | 방어 단계 |
|------|------|----------|
| `Payment.amount` | `Long` / DB `BIGINT` | 타입 |
| `Payment` 생성자 | `amount >= 0` | 코드 |
| `payment` 테이블 | `CHECK (amount >= 0)` | DB |
| `PaymentPrepareRequest.expectedAmount` | `@NotNull + @PositiveOrZero` (`Long`) | 컨트롤러 `@Valid` |
| `PaymentConfirmRequest.amount` | `@NotNull + @PositiveOrZero` (`Long`) | 컨트롤러 `@Valid` |

## 관련 문서

- [Order 도메인](order.md) — 주문 생성/취소 흐름 전체
- [Saga 보상 트랜잭션](../architecture/saga-compensation.md)
