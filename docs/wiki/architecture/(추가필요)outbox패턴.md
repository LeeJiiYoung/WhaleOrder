# Outbox 패턴 도입 계획

> **상태: 미도입 (계획 문서)** — 현재 코드에 outbox 테이블/워커 없음. 이 문서는 나중에 실서비스 이관 시 어디에 어떻게 붙일지 정리한 설계 초안.

## 왜 필요한가

DB 커밋과 외부 시스템(Kafka, PG) 호출을 **하나의 트랜잭션으로 묶을 수 없다**는 근본 문제 때문. 다음 두 상황이 발생 가능:

1. DB 는 커밋됐는데 외부 호출이 실패 → 데이터 불일치
2. 외부 호출은 성공했는데 DB 커밋 실패 → 유령 트랜잭션

Outbox 는 "외부 호출을 DB 트랜잭션 안에서 *예약만* 하고, 별도 워커가 실제 호출을 담당" 하는 방식으로 이 갭을 없앤다.

## 적용해야 할 지점 2곳

### 지점 1: Kafka 발행 (`OrderEventListener.publishOrderEvent`)

**현재 코드** (`src/main/java/com/whale/order/domain/order/event/OrderEventListener.java`)

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderCreated(OrderCreatedEvent event) {
    boolean publishOk = publishOrderEvent(event);   // ← kafkaTemplate.send().get()
    boolean cartOk = clearCustomerCart(event);
}
```

**실패 시나리오**
- DB 커밋 완료 → Kafka 브로커 다운 → publish 실패
- 결제 DB엔 주문이 남았지만 Consumer 가 재고 차감/SSE 알림을 하지 못함 → 유령 주문
- 현재는 `order.event.publish.failure` 메트릭만 카운트, 주석에 "수동 재처리 필요"로 명시

**Outbox 적용안**

```sql
CREATE TABLE kafka_outbox (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    aggregate_id VARCHAR(64),      -- orderId 등, 파티션 키
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP,
    status VARCHAR(20) NOT NULL,   -- PENDING / PUBLISHED / FAILED
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX idx_kafka_outbox_pending ON kafka_outbox(status, created_at)
    WHERE status = 'PENDING';
```

```
PaymentService.pay() 트랜잭션 안에서:
  1. INSERT payment
  2. INSERT kafka_outbox (topic='order-created', payload={orderId}, status=PENDING)
  3. COMMIT

별도 워커 (스케줄러 또는 별도 프로세스):
  4. SELECT * FROM kafka_outbox WHERE status='PENDING' ORDER BY created_at LIMIT 100
  5. 각 row 에 대해 kafkaTemplate.send(topic, payload)
  6. 성공 → UPDATE status='PUBLISHED', published_at=NOW()
     실패 → UPDATE attempts+=1, last_error=?  (일정 횟수 초과 시 status='FAILED')
```

**핵심 이점**
- Kafka 가 몇 시간 다운돼도 outbox 에 쌓여 있다가 복구 시 자동 발행
- `@TransactionalEventListener` 는 여전히 유지하되, 리스너가 하는 일이 "Kafka 발행" 이 아니라 "outbox row 상태 확인" 정도로 축소
- 대신 발행이 즉시가 아니라 **워커 폴링 주기만큼 지연** — Kafka Consumer 처리도 그만큼 지연됨

**폴링 vs Change Data Capture(CDC)**
- 폴링: 단순, JVM 안에 워커 두면 됨. 지연시간 = 폴링 간격
- CDC (Debezium 등): PostgreSQL WAL 을 실시간으로 읽어 Kafka 로 흘림. 지연 거의 없음. 인프라 복잡도 증가
- **1차 도입은 폴링**, 트래픽 커지면 CDC 검토

### 지점 2: PG 결제 승인 (`PaymentService.pay` 내 Mock PG 호출부)

**현재 코드** (`src/main/java/com/whale/order/domain/payment/service/PaymentService.java`)

```java
// Mock PG 결제 처리 (90% 성공)
boolean success = ThreadLocalRandom.current().nextInt(100) < 90;

if (success) {
    String txId = "MOCK-" + UUID.randomUUID()...;
    payment.success(txId);
    ...
}
```

지금은 Mock 이라 실제 외부 API 호출이 없지만, **실제 PG(카카오페이, 토스페이먼츠 등) 로 교체 시 outbox 필수**.

**실패 시나리오**
- PG 승인 API 호출 성공 → 우리 DB 커밋 직전 JVM 크래시
- 사용자 카드는 청구됐는데 우리 시스템엔 결제 기록 없음
- 사용자 재시도 → 이중 청구

**Outbox 적용안 (실제 PG 연동 시)**

```sql
CREATE TABLE payment_outbox (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,      -- APPROVE / CANCEL
    request_payload JSONB NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,  -- PG 사에 전달할 키
    status VARCHAR(20) NOT NULL,
    pg_txn_id VARCHAR(100),           -- PG 응답
    ...
);
```

```
pay() 트랜잭션 안에서:
  1. INSERT payment (status=PENDING)
  2. INSERT payment_outbox (action=APPROVE, idempotency_key=?)
  3. COMMIT

별도 결제 워커:
  4. PENDING outbox 읽기
  5. PG API 호출 (Idempotency-Key 헤더 필수)
  6. 응답에 따라 payment.status 업데이트 + outbox status 변경
     - 크래시 후 재실행 시에도 PG 는 같은 key 로 중복 호출 무시 → 정확히 1번 승인
```

**주의점**
- PG 사가 `Idempotency-Key` 헤더를 지원해야 함 (대부분의 국내 PG 지원)
- 결제 완료 통지가 사용자에게 실시간이 아니라 워커 폴링 주기만큼 지연 → SSE 로 커버
- 결제 취소도 같은 패턴 (`action=CANCEL`)

## 도입 우선순위

| 순위 | 대상 | 이유 |
|-----|------|------|
| 1 | **PG 결제 승인** (실 PG 이관 시) | 돈이 걸림. 이중 청구는 CS 폭발 유발 |
| 2 | Kafka 발행 (order-created) | 유령 주문 방지. 지금은 메트릭 수동 대응이라 트래픽 커지면 감당 안 됨 |
| 3 | (선택) 결제 취소 · 재고 복구 이벤트 | 파급 효과가 크지 않음. 있으면 좋은 수준 |

## 도입 시 함께 고민할 것

- **워커 이중화**: 워커 자체가 SPOF 가 되면 안 됨. 리더 선출(예: Redis lock) 로 여러 인스턴스 중 하나만 동작하거나, DB row-level lock (`FOR UPDATE SKIP LOCKED`) 으로 안전하게 분산
- **outbox 테이블 청소**: PUBLISHED row 는 일정 기간(7일 등) 후 삭제하는 배치. 안 그러면 무한 커짐
- **모니터링**: `kafka_outbox_pending_count` 를 Prometheus 지표로 노출. 갑자기 늘면 워커 문제 알림
- **순서 보장**: 같은 aggregate(orderId 등) 이벤트는 순서대로 발행되어야 함. 워커에서 파티션 키 단위 순서 처리 로직 필요
- **트랜잭션 격리 수준**: 폴링 시 다른 워커가 동시에 같은 row 를 잡지 않도록 `SKIP LOCKED` 필수

## 관련 대화

- 2026-07-23: Kafka 브로커 다운 시 프로듀서 실패 대비 논의 중 첫 언급
- 2026-07-23: 결제 멱등성 Redis-only 위험 분석에서 층 5 방어책으로 재언급

## 참고
- [Idempotency 리스크 분석]((추가필요)멱등redis에%20추가할것.md) — 왜 Redis 만으로는 부족한지, PG 크래시 시나리오 등
- [Kafka 이벤트 스트림](kafka-event-stream.md) — 현재 발행 흐름