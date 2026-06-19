# Order — 주문

> 주문 생성/처리/상태관리. 시스템의 중심 도메인. SSE 푸시 · Kafka 비동기 처리 · 상태 이력 관리 모두 포함.

**디렉토리**: `src/main/java/com/whale/order/domain/order/`

## 구성

| 분류 | 파일 |
|------|------|
| Entity | `Orders`, `OrderItem`, `OrderStatus`, `OrderStatusHistory`, `OrderType` |
| Controller | `CustomerOrderController`, `AdminOrderController`, `OrderQueueController` |
| Service | `OrderService`, `OrderProcessingService`, `OrderCancelService`, `OrderQueueService`, `OrderQueueWorker`, `OrderKafkaProducer`, `OrderKafkaConsumer`, `OrderSseService` |
| Event | `OrderCreatedEvent`, `OrderEventListener` |
| Repository | `OrderRepository`, `OrderStatusHistoryRepository` |

## 생명주기

```
PENDING → ACCEPTED → PREPARING → READY → COMPLETED
                                              └─ CANCELLED (어디서든 가능)
```

모든 상태 전이는 `OrderStatusHistory` 에 기록.

## 핵심 플로우

### 주문 생성 — 선결제 (`PaymentService.pay()`)

주문 **생성** 진입점은 결제 도메인의 `PaymentService.pay()`. `OrderService` 는 조회·취소·상태 변경만 담당.

```
1. 장바구니 조회 + 매장 OPEN / 메뉴 isOnSale / Stock 존재 검증
2. SHA-256 멱등성 키 (memberId : storeId : method : orderType : cart)
3. Orders + Payment(PENDING) 저장
4. Mock 결제 90% / 실패 시 throw → 전체 롤백
5. 성공 시 OrderCreatedEvent 발행 (AFTER_COMMIT)
6. ━ outer 트랜잭션 commit ━
7. OrderEventListener → Kafka publish + 장바구니 비우기
```

→ 자세한 결제 흐름은 [Payment 도메인](payment.md) 참조.

### Consumer 처리 (`OrderProcessingService`)

```
1. 재고 차감 (StockLockFacade)
2. stockDeducted = true
3. SSE result/newOrder 전파
4. 실패 시 → 부분 복구 + OrderCancelService.cancelOrder() (재고 환불 + 결제 환불)
```

### 어드민 상태 전이 (`OrderService.changeStatus`)

```
어드민 액션 → OrderService.changeStatus()
             → OrderStatusHistory 기록
             → OrderSseService.notifyStatusUpdate() (고객)
             → OrderSseService.broadcastOrderStatusChange() (어드민들)
```

지원 액션: `prepare` (PENDING→PREPARING) · `complete` (PREPARING→COMPLETED) · `cancel` (아래 어드민 주문 취소 참조). OWNER 는 본인 매장 주문만 처리 가능.

### 고객 주문 취소 (`OrderService.cancelOrder`)

```
1. 본인 주문 검증
2. order.cancel() — PENDING 상태만 허용
3. stockDeducted == true 면 재고 복구
4. paymentService.cancelPayment(order, "고객 주문 취소") — SUCCESS 결제만 환불
5. OrderStatusHistory(CANCELLED) 기록
```

### 어드민 주문 취소 (`OrderService.changeStatus("cancel")`)

```
1. 본인 매장 검증 (OWNER 한정, ADMIN 은 제한 없음)
2. order.cancelByAdmin() — PENDING/PREPARING 둘 다 허용 (완료·이미 취소된 주문은 예외)
3. stockDeducted == true 면 StockLockFacade.restoreStock() 으로 재고 복구
4. paymentService.cancelPayment(order, "관리자 주문 취소") — SUCCESS 결제만 환불
5. OrderStatusHistory(CANCELLED, changedBy=admin) 기록
6. SSE 고객 푸시: "매장 사정으로 주문이 취소되었습니다. 결제는 환불 처리됩니다."
7. SSE 어드민 브로드캐스트로 다른 어드민 화면도 동기화
```

#### 고객 cancel vs 어드민 cancelByAdmin

| 구분 | 고객 `cancel()` | 어드민 `cancelByAdmin()` |
|---|---|---|
| 허용 상태 | `PENDING` 만 | `PENDING`, `PREPARING` |
| 진입점 | `DELETE /api/orders/{id}` | `PATCH /api/admin/orders/{id}/cancel` |
| 권한 | 본인 주문 | OWNER(본인 매장) / ADMIN |
| 환불 사유 라벨 | "고객 주문 취소" | "관리자 주문 취소" |

## 관련 문서

- [실시간 SSE 푸시](../architecture/realtime-sse.md)
- [Saga 보상 트랜잭션](../architecture/saga-compensation.md)
- [Kafka 이벤트 스트림](../architecture/kafka-event-stream.md)
- [동시성 제어](../architecture/concurrency-control.md)
