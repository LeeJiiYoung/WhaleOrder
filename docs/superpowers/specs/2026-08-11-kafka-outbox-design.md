# Kafka Outbox 도입 설계

> **상태**: 설계 완료 (구현 대기) · **작성일**: 2026-08-11
> **적용 범위**: `order-created` 이벤트 발행 (지점 1). `payment_outbox`(지점 2)는 이번 스코프 밖.

## 1. 목표

`PaymentService.pay()` 트랜잭션 커밋 이후 발생하는 Kafka 발행 실패로 인한 **유령 주문**(주문은 DB에 있는데 재고 차감·SSE 알림이 없는 상태)을 없앤다. DB 커밋과 Kafka 발행 예약을 하나의 트랜잭션으로 묶어 원자성을 확보한다.

**부수 이득 (prod 프로필)**: EC2 프리티어 배포에서 Kafka 없이 `OrderProcessingService.process()` 를 동기 호출로 폴백하는 현재 구조에서도, 커밋 후 `process()` 호출 직전 JVM 크래시 시 발생하는 유령 주문을 outbox 재시도로 자동 복구한다.

## 2. 스코프

**포함**
- `kafka_outbox` 테이블 신규 도입
- `PaymentService.pay()` 안에서 outbox row 저장
- 폴링 워커 (`@Scheduled` 1초 주기)
- Kafka 있음/없음에 따른 발행 방식 분기 (워커 안에서)
- 청소 배치 (7일 지난 PUBLISHED row 삭제, 매일 새벽 3시)
- Prometheus 지표 3종
- 관련 wiki 문서 정리 (`(추가필요)outbox패턴.md` → `outbox.md`)

**미포함**
- `payment_outbox` (실 PG 이관 시 별도 스펙으로)
- Change Data Capture (Debezium 등)
- 이벤트 트리거 하이브리드 (즉시 발행) — 폴링 부하가 실측상 문제 되면 재검토
- FAILED 상태 알림(Slack/이메일) — 대시보드 카운터로만
- 다중 인스턴스용 `SELECT ... FOR UPDATE SKIP LOCKED` — 단일 인스턴스 전제 유지

## 3. 아키텍처

### Before

```
PaymentService.pay() [트랜잭션]
  ├─ INSERT payment
  ├─ INSERT order
  ├─ eventPublisher.publishEvent(OrderCreatedEvent)
  └─ COMMIT
                ↓ [AFTER_COMMIT]
OrderEventListener
  ├─ Kafka publish  (or 폴백: process() 직접 호출)  ← 실패 시 유령 주문
  └─ cart clear
```

문제: 커밋 후 Kafka 발행 실패 시 재처리 수단이 메트릭 알림 + 수동 대응밖에 없음.

### After

```
PaymentService.pay() [트랜잭션]
  ├─ INSERT payment
  ├─ INSERT order
  ├─ outboxService.enqueue("order-created", orderId, payload)   ← INSERT kafka_outbox
  ├─ eventPublisher.publishEvent(OrderCreatedEvent)               ← cart clear 용도로만 유지
  └─ COMMIT  (여기까지 원자적)
                ↓
        ┌───────┴──────────────────────┐
        ↓                              ↓
KafkaOutboxWorker [1초 폴링]     OrderEventListener [AFTER_COMMIT]
  ├─ PENDING 100건 조회             └─ cart clear
  ├─ 각 row 개별 트랜잭션으로:
  │   ├─ dev: kafkaTemplate.send()
  │   └─ prod: processingService.process()
  ├─ 성공 → PUBLISHED
  └─ 실패 → attempts++, 5회 초과 시 FAILED

[별도 스케줄러] 매일 새벽 3시
  → 7일 지난 PUBLISHED row 삭제
```

**핵심 변화 3가지**
1. `pay()` 트랜잭션 안에 outbox row INSERT 추가 → DB 커밋과 "발행 예약"이 원자적.
2. Kafka 발행/폴백 분기가 리스너에서 **워커로 이동**. 리스너는 cart clear 전용으로 축소.
3. 발행 실패해도 outbox에 PENDING 으로 남아 자동 재시도 → 유령 주문 해소.

## 4. 데이터 모델

```sql
CREATE TABLE kafka_outbox (
    id            BIGSERIAL PRIMARY KEY,
    topic         VARCHAR(100) NOT NULL,
    aggregate_id  BIGINT       NOT NULL,   -- orderId. Kafka 파티션 키로 사용
    payload       JSONB        NOT NULL,
    status        VARCHAR(20)  NOT NULL,   -- PENDING / PUBLISHED / FAILED
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMP
);

-- 폴링 최적화: PENDING인 것만 인덱스에 존재 (partial index)
CREATE INDEX idx_kafka_outbox_pending
    ON kafka_outbox (created_at)
    WHERE status = 'PENDING';

-- 청소 배치 최적화
CREATE INDEX idx_kafka_outbox_published_at
    ON kafka_outbox (published_at)
    WHERE status = 'PUBLISHED';
```

**컬럼 설계 이유**
- `aggregate_id` = BIGINT: 이 프로젝트의 모든 aggregate ID (orderId, paymentId, memberId, storeId, menuId)가 Long. 유연성 대신 타입 안전 + 변환 코드 제거 선택.
- `payload` = JSONB: 문자열보다 검색·디버깅 편함. Postgres 사용 프로젝트라 자연스러움.
- `topic`: 나중에 다른 이벤트(`payment-cancelled` 등)도 붙일 수 있는 범용 컬럼.

**Partial Index**
- PENDING partial index: 처리 끝난 row는 자동으로 인덱스에서 빠짐. 폴링 부하 최소화의 핵심.
- PUBLISHED partial index: 청소 배치 `WHERE status='PUBLISHED' AND published_at < NOW() - 7 days` 최적화.

**엔티티 매핑 (JPA)**
- `KafkaOutbox` 엔티티에 `enqueue()`, `markPublished()`, `markFailedOrRetry(Exception, int maxAttempts)` 상태 전이 메서드를 두어 원시 setter 노출 안 함.
- Payload는 Hibernate 6 `@Type(JsonBinaryType.class)` 또는 String 필드 중, 이 프로젝트 기존 JSONB 사용처의 방식을 따름. 기존 사용처 없으면 String으로 단순화.

**Payload 형식 (`order-created` 기준)**
```json
{ "orderId": 123 }
```
현재는 Consumer가 orderId 하나만 필요. 향후 이벤트 추가 시 스키마 확장 (예: `payment-cancelled` → `{ "orderId": 123, "reason": "USER_CANCELLED" }`).

## 5. 컴포넌트 구성

### 신규 (5개, `src/main/java/com/whale/order/global/outbox/`)

| 컴포넌트 | 책임 |
|---------|------|
| `KafkaOutbox` (엔티티) | 상태와 재시도 카운트 소유. 상태 전이는 도메인 메서드로만 |
| `KafkaOutboxRepository` | `findTop100ByStatusOrderByCreatedAtAsc(PENDING)`, `deleteByStatusAndPublishedAtBefore(PUBLISHED, cutoff)` |
| `KafkaOutboxService` | 유일 public API `enqueue(topic, aggregateId, payload)`. `@Transactional(propagation=REQUIRED)` — 호출자 트랜잭션 참여 |
| `KafkaOutboxWorker` | `@Scheduled(fixedDelay=1000)` 폴링. 각 row 별 개별 트랜잭션. Kafka 있음/없음 분기 |
| `KafkaOutboxCleanupJob` | `@Scheduled(cron="0 0 3 * * *")` 매일 새벽 3시. 7일 지난 PUBLISHED 삭제 |

**위치 선정**: outbox는 특정 도메인 소유가 아닌 인프라성 컴포넌트. `global/idempotency/`, `global/config/`와 동일 층위.

### 기존 코드 변경 지점 (2개)

**1. `PaymentService.pay()` (line ~207)**
```java
// 기존
eventPublisher.publishEvent(new OrderCreatedEvent(order.getOrderId(), memberId));

// 변경 후
kafkaOutboxService.enqueue("order-created", order.getOrderId(), toJson(order.getOrderId()));
eventPublisher.publishEvent(new OrderCreatedEvent(order.getOrderId(), memberId)); // cart clear용
```

**2. `OrderEventListener`**
- `publishOrderEvent()` 메서드 및 `Optional<OrderKafkaProducer>`, `OrderProcessingService` 의존성 제거.
- `clearCustomerCart()` 만 남김. 클래스 주석·이름도 이에 맞춰 정리.

### 손대지 않는 것

- `OrderKafkaProducer`, `OrderKafkaConsumer`, `OrderProcessingService`, `StockLockFacade`, `OrderSseService` 유지.
- Consumer 쪽 중복 방어(`OrderProcessingService.processOrder()` 의 `isStockDeducted` 체크)는 이미 있어서 outbox 도입 후 발생 가능한 중복 발행도 자연스럽게 흡수.

### 원자성 지키기 위한 3가지 규칙 (⚠️ 리뷰 시 필수 체크)

1. `KafkaOutboxService.enqueue()` 안에서 **예외를 catch로 삼키지 않는다**. 그대로 상위로 던져야 `pay()`가 롤백된다.
2. `pay()` 안에서 `enqueue()` 호출을 try-catch로 감싸지 않는다. 감싸는 순간 "이벤트 없는 결제 성공" 시나리오가 부활한다.
3. `KafkaOutboxService`는 `@Transactional(propagation=REQUIRED)` (기본값). `REQUIRES_NEW`는 절대 금지.

## 6. 데이터 흐름 (정상 케이스)

```
T=0.000s  pay() 시작
T=0.010s  BEGIN TX
T=0.020s  INSERT payment
T=0.025s  INSERT order
T=0.030s  INSERT kafka_outbox (status=PENDING)
T=0.035s  publishEvent(OrderCreatedEvent)
T=0.050s  COMMIT
T=0.055s  AFTER_COMMIT 리스너 → cartService.clearCart()
T=0.060s  사용자 응답 반환
...
T=0.500s  워커 폴링 (평균 대기 = 폴링주기/2)
T=0.510s  BEGIN TX → publish → markPublished → COMMIT
T=0.520s  Consumer 수신 (dev) / (prod은 워커가 직접 process 호출)
T=0.530s  재고 차감 완료 → SSE 알림
```

**결제 완료 → 재고 차감 완료까지: 평균 500ms, 최악 1초 지연**. 현재는 사실상 즉시(수십 ms). UX 영향은 SSE로 완화 가능.

**워커 트랜잭션을 row 별로 여는 이유**: 100건을 한 트랜잭션으로 묶으면 한 건 실패가 나머지 99건 발행을 막음. row 별 트랜잭션이면 실패는 그 row 만 재시도 대상.

## 7. 실패 시나리오 매트릭스

| # | 실패 지점 | 결과 | 복구 |
|---|---------|------|------|
| 1 | `outbox INSERT` 실패 (DB 이슈) | `pay()` 전체 롤백 | 사용자 재시도. 결제/주문/outbox 모두 없음. **완벽한 원자성** |
| 2 | `pay()` 커밋 직후 JVM 크래시 | outbox row = PENDING 로 남음 | 재시작 후 워커가 자동 발행 |
| 3 | Kafka 브로커 다운 | 워커 발행 실패 → attempts++ | 브로커 복구 후 다음 폴링 자동 재시도 |
| 4 | 워커 send 성공, `UPDATE PUBLISHED` 실패 | Kafka엔 발행, DB엔 PENDING 유지 | 다음 폴링 재발행 → Consumer가 `isStockDeducted` 로 흡수 |
| 5 | `send().get()` 타임아웃 | attempts++, PENDING 유지 | 다음 폴링 재시도 |
| 6 | 5회 재시도 후에도 실패 | status=FAILED | 폴링 대상 제외. `kafka_outbox_failed_total` 증가. **수동 개입** |
| 7 | prod 모드 `process()` 실행 중 예외 | attempts++, PENDING 유지 | 다음 폴링 재시도 |
| 8 | 워커 스레드 죽음 | 폴링 자체 중단 | `kafka_outbox_pending_count` 증가로 알림 감지 |
| 9 | 청소 배치 실패 | 오래된 PUBLISHED 안 지워짐 | 다음 날 새벽 3시 재시도 |

### 워커 핵심 로직

폴링 루프와 row 별 트랜잭션 처리를 **별도 빈으로 분리**한다. 같은 클래스 안에서 `this.processOne()` 을 호출하면 Spring 프록시를 우회해서 `@Transactional` 이 안 걸리기 때문 (self-invocation 함정).

```java
// KafkaOutboxWorker — 폴링 루프
@Component
@RequiredArgsConstructor
public class KafkaOutboxWorker {
    private final KafkaOutboxRepository outboxRepository;
    private final KafkaOutboxRowProcessor rowProcessor;   // ← 별도 빈

    @Scheduled(fixedDelay = 1000)
    public void publishPending() {
        List<KafkaOutbox> pending = outboxRepository
            .findTop100ByStatusOrderByCreatedAtAsc(PENDING);
        for (KafkaOutbox row : pending) {
            try {
                rowProcessor.processOne(row.getId());   // 프록시 경유 → @Transactional 작동
            } catch (Exception e) {
                log.error("[Outbox] 워커 처리 실패 outboxId={}", row.getId(), e);
            }
        }
    }
}

// KafkaOutboxRowProcessor — row 별 개별 트랜잭션
@Component
@RequiredArgsConstructor
public class KafkaOutboxRowProcessor {
    private final KafkaOutboxRepository outboxRepository;
    private final Optional<OrderKafkaProducer> kafkaProducer;
    private final OrderProcessingService processingService;

    @Transactional
    public void processOne(Long outboxId) {
        KafkaOutbox row = outboxRepository.findById(outboxId).orElseThrow();
        try {
            publish(row);                  // dev: kafka.send / prod: process()
            row.markPublished();
        } catch (Exception e) {
            row.markFailedOrRetry(e, 5);   // attempts++, 5회 초과 시 FAILED
            // 트랜잭션 커밋되어 attempts 반영
        }
    }
}
```

**설계 포인트 2가지**
1. `processOne(Long outboxId)` — 엔티티가 아니라 ID를 넘긴다. 워커 트랜잭션 밖에서 로드한 엔티티는 detached 상태라 dirty checking 이 안 걸림.
2. `catch` 로 예외를 삼키는 건 의도적. 상태 업데이트 트랜잭션은 커밋되어야 attempts 가 반영됨. 예외 다시 던지면 트랜잭션 롤백으로 attempts 도 롤백됨.

### At-Least-Once 전제

Outbox는 근본적으로 at-least-once. 발행 후 상태 업데이트 전 크래시 시 재발행 발생. 그래서 Consumer 멱등성이 도입 전제 조건 — 이 프로젝트는 `OrderProcessingService.processOrder()` line 70에 이미 방어 있음 (`if (order.isStockDeducted() || CANCELLED) return;`).

## 8. 테스트 전략

**모든 통합 테스트는 `com.whale.order.support.TestContainerBase` 상속** (기존 프로젝트 표준. PostgreSQL + Redis + EmbeddedKafka 기 세팅).

### 단위 테스트 (Mockito)

| 대상 | 검증 |
|-----|-----|
| `KafkaOutbox` 엔티티 | 생성 시 PENDING/attempts=0 · `markPublished()` 상태 전이 · `markFailedOrRetry(e, 5)` 임계 동작 |
| `KafkaOutboxWorker` | Kafka 유무별 호출 분기 · 성공 시 markPublished · 실패 시 attempts++ |

### 통합 테스트

| 시나리오 | 검증 |
|--------|-----|
| 원자성(성공) | `pay()` 성공 후 payment/order/kafka_outbox 세 row 모두 존재 |
| 원자성(실패) | Repository.save mock 예외 → payment/order/outbox 모두 없음 |
| 워커 정상 발행 | PENDING row 준비 → 워커 실행 → PUBLISHED, published_at 세팅 확인 |
| 워커 발행 실패 | KafkaTemplate mock 예외 → PENDING 유지, attempts=1 |
| 재시도 임계 | attempts=4 row → 실패 → attempts=5 PENDING → 재실패 → FAILED |
| 청소 배치 | 8일 전 PUBLISHED 삭제 확인, 6일 전 row 유지 확인 |

### 프로필별 테스트

| 프로필 | 검증 |
|-------|-----|
| dev (`kafka.enabled=true`, 기본) | 워커가 `kafkaTemplate.send()` 호출 |
| prod (`kafka.enabled=false` via `@TestPropertySource`) | 워커가 `processingService.process()` 호출, kafkaTemplate 참조 없음 |

### 기존 테스트 영향

- `SagaCompensationTest`: 기존 흐름 검증 중이면 outbox 경유로 바뀐 것에 맞춰 최소 조정.
- `OrderEventListener` 관련 테스트: `publishOrderEvent` 케이스 삭제 or 이관.

### 부하 테스트 (선택)

k6 스크립트로 outbox 도입 전/후 비교 (필수 아님, 성능 특성 문서화 목적).
- pay() p50/p95/p99 — INSERT 한 줄 추가라 거의 무영향 예상
- 재고 차감 e2e 지연 — 평균 500ms 증가 예상
- outbox 테이블 성장 속도

## 9. 모니터링 · 운영

### Prometheus 지표

| 지표 | 타입 | 의미 | 알림 임계 |
|-----|-----|-----|---------|
| `kafka_outbox_pending_count` | Gauge | 현재 PENDING row 수 | > 500 지속 5분 = 워커/브로커 이상 |
| `kafka_outbox_published_total` | Counter | 누적 발행 성공 | baseline 대비 급감 시 |
| `kafka_outbox_failed_total` | Counter | FAILED 전환 수 | 증가 자체가 이상 신호 |

### Grafana 패널 (기존 대시보드에 추가)

```
Outbox Pending (실시간 gauge)  | Outbox Published Rate (rpm)
Outbox Failed (누적)            | Outbox Publish Latency*
```
`*Publish Latency = published_at - created_at`. 정상 시 평균 500ms 근방.

### 운영 런북 항목 (`docs/wiki/operations/ec2-runbook.md` 에 추가)

| 증상 | 확인 | 조치 |
|-----|-----|-----|
| PENDING 급증 | Kafka 브로커 상태, 워커 로그 | Kafka 재시작 → 자동 catch-up |
| FAILED 증가 | `SELECT * FROM kafka_outbox WHERE status='FAILED'` last_error | 원인별 조치, 필요 시 수동 status=PENDING 복구 |
| outbox 테이블 급성장 | `KafkaOutboxCleanupJob` 로그 | 배치 수동 실행, 크론 확인 |
| dev/prod 분기 오작동 | 워커 로그의 발행 방식 표시 | `kafka.enabled` 프로퍼티 확인 |

### 기존 메트릭 정리

- `order.event.publish.failure` (기존 `OrderEventListener` 카운터) → **제거**. 워커의 `kafka_outbox_failed_total` 이 대체.
- `cart.clear.failure` → 유지 (여전히 리스너에서 발생 가능).

### 로그 정책

- `KafkaOutboxService.enqueue()`: INFO, orderId 만
- `KafkaOutboxWorker.publishPending()`: DEBUG, 건수만 (INFO 매초 = 스팸)
- 발행 실패: WARN, attempts 명시
- FAILED 전환: ERROR + 지표 카운터

## 10. Wiki 문서 정리

- `docs/wiki/architecture/(추가필요)outbox패턴.md` → **`docs/wiki/architecture/outbox.md`** 로 rename.
- 문서 상단 상태 "미도입 (계획 문서)" → "도입 완료 (지점 1: Kafka outbox)".
- 지점 2(payment_outbox)는 여전히 계획 상태로 남김.
- `docs/wiki/Home.md` 링크 텍스트 업데이트.

## 11. 미결정 · 향후 과제

- **payment_outbox (지점 2)**: 실 PG 이관 시 별도 스펙.
- **이벤트 트리거 하이브리드**: 폴링 지연이 실측상 문제 되면 도입 검토. `applicationEventPublisher` 로 `outboxSaved` 이벤트 발행 → 워커 wake-up + 폴링은 백업.
- **다중 인스턴스 워커**: 인스턴스 늘어날 계획 생기면 `SELECT ... FOR UPDATE SKIP LOCKED` 추가. SSE 채널의 인메모리 `ConcurrentHashMap` 한계와 함께 다뤄야 하는 이슈.
- **FAILED 알림**: 트래픽 커지면 Slack/이메일 연동 검토.

## 12. 관련 문서

- [Outbox 패턴 원안](../wiki/architecture/(추가필요)outbox패턴.md) — 지점 1·2 배경, 폴링 vs CDC 비교
- [결제 멱등성 층별 방어](../wiki/architecture/(추가필요)멱등redis에%20추가할것.md) — 층 5 로 outbox 언급
- [Kafka 이벤트 스트림](../wiki/architecture/kafka-event-stream.md) — 현재 발행 흐름
- [SSE 재연결 계획](../wiki/architecture/(추가필요)sse-재연결.md) — 인메모리 Map 한계, 스케일아웃 이슈