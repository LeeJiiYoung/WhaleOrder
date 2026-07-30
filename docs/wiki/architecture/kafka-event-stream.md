# Kafka 이벤트 스트림

> 주문 후처리를 요청 스레드에서 떼어내 피크 시 서비스 전체 마비를 막고, DLT로 유실 없는 실패 처리를 보장

**관련 코드**
- `src/main/java/com/whale/order/global/config/KafkaConfig.java`
- `src/main/java/com/whale/order/domain/order/service/OrderKafkaProducer.java`
- `src/main/java/com/whale/order/domain/order/service/OrderKafkaConsumer.java`
- `src/main/java/com/whale/order/domain/order/event/OrderEventListener.java`

## 왜 Kafka인가 — "응답이 빨라진다"는 부정확

흔한 오해: *"비동기라 사용자에게 빠른 응답을 준다"*. 실제로는 **체감 응답 시간이 줄지 않는다.**

- 사용자가 최종 결과(재고 확보 성공/실패)를 보는 건 어차피 Consumer 처리 후 도착하는 SSE다.
- 발행조차 논블로킹이 아니다 — `OrderKafkaProducer.publish()` 는 `.get()` 으로 브로커 ack까지 **블로킹**한다.
- 브로커 왕복이 추가되므로 정상 경로 지연은 오히려 **소폭 증가**한다.

### 실제 이득 3가지

| 이득 | 설명 |
|------|------|
| **요청 스레드 점유 해제 (가장 큼)** | 동기 처리 시 `tryLock(5s)` 락 대기 동안 톰캣 스레드가 물린다. 운영은 `max-threads: 50` — 인기 메뉴 하나에 50명이 몰리면 **메뉴 조회·로그인 등 주문과 무관한 API까지 정지**한다. Kafka를 끼면 락 경합이 Consumer(파티션 3)로 격리되어 나머지 API가 산다. |
| **유실 없는 실패 처리** | 동기 경로엔 재시도 장치가 없다. Kafka 경로만 `FixedBackOff(1s×3)` → DLT → `compensate()` 자동 보상이 성립. |
| **폭증 버퍼링** | 순간 유입을 브로커가 흡수하고 Consumer가 자기 속도로 소비. |

즉 Kafka가 지키는 것은 "주문한 사람의 응답 속도"가 아니라 **주문하지 않은 사용자의 API 가용성**과 **실패 주문의 자동 복구**다.

### HTTP 응답과 SSE의 역할 분담

선결제 구조라 두 신호의 의미가 처음부터 다르다. **Kafka를 꺼도 SSE는 여전히 필요하다.**

| 신호 | 의미 |
|------|------|
| HTTP 200 | 결제 완료 · 주문 접수됨 (금액 확정) |
| SSE | 재고 확보 성공 / 부족으로 취소 (판정) |

`process()` 는 `AFTER_COMMIT` 에서 실행되므로 **이미 결제가 커밋된 뒤**다. 재고 차감이 실패해도 롤백되지 않고 `OrderEventListener` 가 예외를 삼켜 사용자는 200을 받는다. 동기 처리로 바꿔도 재고 결과를 HTTP 응답에 담을 수 없다.

## 동기 폴백 모드 (`kafka.enabled: false`)

`OrderEventListener` 는 Kafka 유무에 따라 두 경로로 갈린다.

```java
orderKafkaProducer.ifPresentOrElse(
        p -> p.publish(event.orderId()),              // kafka.enabled=true
        () -> orderProcessingService.process(...)     // kafka.enabled=false → 같은 스레드에서 동기 호출
);
```

| | Kafka OFF | Kafka ON |
|---|---|---|
| 체감 응답 시간 | 락 대기 포함 → 피크에 최대 5초 | 사실상 동일 (브로커 홉만큼 +) |
| SSE 필요 여부 | 필요 | 필요 |
| 피크 시 타 API | 같이 마비 | 살아 있음 |
| 재고 차감 실패 | 로그 + 메트릭만, **수동 재처리** | 재시도 → DLT → 자동 보상 |

**현재 운영 배포(`prod`)는 Kafka OFF로 동작한다** — 프리티어 리소스 제약 때문. `application-prod.yaml` 에서 `KafkaAutoConfiguration` 을 제외하고 `kafka.enabled: false` 로 커스텀 빈까지 끈다. (둘 중 하나만 하면 `KafkaTemplate` 주입 실패로 앱이 뜨지 않는다.)

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
