# Kafka 이벤트 스트림

> 주문 생성 트래픽을 비동기로 흘려보내 응답 지연을 줄이고, DLT로 유실 없는 실패 처리를 보장

**관련 코드**
- `src/main/java/com/whale/order/global/config/KafkaConfig.java`
- `src/main/java/com/whale/order/domain/order/service/OrderKafkaProducer.java`
- `src/main/java/com/whale/order/domain/order/service/OrderKafkaConsumer.java`

## 토픽 구성

| 토픽 | 파티션 | 복제본 | 용도 |
|------|--------|--------|------|
| `order-created` | 3 | 1 (개발) / 3 (운영) | 주문 생성 이벤트 |
| `order-created.DLT` | 1 | 1 | 3회 재시도 실패 메시지 보관 |

파티션 3은 Consumer 3개 병렬 처리를 가능하게 함. orderId 키 해싱으로 같은 주문은 같은 파티션에 떨어져 순서 보장.

## 에러 핸들러

```java
new DefaultErrorHandler(
    new DeadLetterPublishingRecoverer(kafkaTemplate),
    new FixedBackOff(1000L, 3)   // 1초 간격 × 3회
);
```

`IllegalArgumentException` / `IllegalStateException` 등 재시도 의미 없는 예외는 `addNotRetryableExceptions()` 로 즉시 DLT 직행.

## 발행 시점 — AFTER_COMMIT

```
주문 트랜잭션 ── commit ─┐
                        ├─► @TransactionalEventListener(AFTER_COMMIT)
                        └─► OrderKafkaProducer.publish()
```

DB 커밋 이후 Kafka 발행 → 커밋 실패 시 메시지 유실 가능성 차단. 장바구니 비우기 등 후속 작업도 같은 보장.

## Consumer 처리 흐름

```
order-created Consumer
   ├─ OrderProcessingService.process(orderId)
   │    └─ 재고 차감 + SSE 푸시
   ├─ 성공 → ack
   └─ 실패 → FixedBackOff(1s×3)
            └─ 모두 실패 → order-created.DLT
                  └─ DLT Consumer → compensate(orderId) → 주문 취소
```

## 멱등성

- Consumer 측: `order.isStockDeducted()` 체크로 중복 처리 방어
- API 측: `IdempotencyService` 가 클라이언트 멱등성 키 검증 (재제출 방지)

## 한계와 다음 단계

- 메시지 스키마 버저닝 미적용 → 추후 Avro/Schema Registry 검토
- DLT Consumer가 단일 노드 → 운영 환경에서는 파티션/replica 늘리기 필요
- 토픽 단일화(`order-created` 만) → 도메인 확장 시 `payment-*`, `stock-*` 분리 검토
