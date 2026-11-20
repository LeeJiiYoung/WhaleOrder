# 결제 멱등성 층 2 (DB UNIQUE) + 층 7 (TTL 조정) 설계

작성일: 2026-08-20
관련 문서: [멱등 Redis 단일 방어의 한계](../../wiki/architecture/(추가필요)멱등redis에%20추가할것.md)

## 배경

현재 결제 멱등성은 Redis `SET NX EX` 단일 방어다 (`IdempotencyService`). 위 문서가 정리한 6가지 시나리오
(마스터-슬레이브 페일오버 갭, PROCESSING TTL 만료, JVM 크래시, saveResult 실패, 카트 JSON 직렬화 비결정성,
클러스터 스플릿브레인) 중 어느 하나라도 발생하면 중복 결제가 성사된다.

이번 작업은 그 문서의 **층 2 (DB UNIQUE 제약)** 와 **층 7 (TTL 조정)** 을 도입한다.
층 3(PG Idempotency-Key 헤더), 층 4(PG 상태 조회 API)는 실제 PG 연동 시점의 별도 작업으로 남긴다.

## 목표 / 비목표

**목표**

- Redis가 뚫린 상황에서도 동일한 결제 요청이 두 번 처리되지 않도록 DB 레벨 최종 방어선을 만든다.
- "Redis가 뚫려서 DB가 막았다"를 관측 가능하게 만든다 (로그 + 메트릭).
- 층 2가 생긴 것을 근거로 PROCESSING TTL을 안전하게 늘린다.

**비목표**

- UNIQUE 위반 시 이전 결제를 조회해 200으로 반환하는 동작. 트랜잭션 바깥 레이어(`PaymentFacade`)가 필요해
  구조 변경이 커지는 반면, 층 2는 정상 경로에선 사실상 발동하지 않는 최후 방어선이다. 409 + 안내 메시지로 충분하다.
- 실제 PG 연동, 층 3/4/5/6.
- 층 1(Redis) 동작 변경. TTL 상수 외에는 건드리지 않는다.

## 핵심 설계 결정 — 두 층은 서로 다른 키를 쓴다

가장 중요한 결정이자, 이 설계의 나머지가 전부 여기서 파생된다.

현재 멱등성 키는 `SHA-256(memberId + storeId + method + orderType + customerRequest + toJson(cart))` 이다.
이것은 **진짜 멱등성 키가 아니라 "카트 해시 + 시간 윈도우" 휴리스틱**이다. 서로 다른 두 개의 진짜 주문도
같은 해시를 가질 수 있어서, TTL 60초로 "이 정도 시간 안의 동일 카트면 실수겠지"라고 추정하는 구조다.

DB UNIQUE 제약에는 시간 개념이 없다. 이 키를 그대로 DB에 영구 저장하면 **해당 카트 구성으로는 영원히 재주문이
불가능**해진다 — 사이렌 오더에서 치명적이다.

키에 시간 버킷을 섞는 해법(`... + floor(now/5분)`)은 채택하지 않는다. 키가 요청의 순수 함수가 아니게 되어
멱등성 자체가 깨지고, 버킷 경계를 넘는 재시도는 방어를 그냥 통과한다. 방어가 있다고 착각하게 만드는 만큼
없는 것보다 나쁠 수 있다.

따라서 **각 층이 다른 키를 쓴다.**

| | 층 1 (Redis) | 층 2 (DB UNIQUE) |
|---|---|---|
| 키 | 카트 해시 (현행 유지) | `requestId` (클라이언트 발급 UUID) |
| 수명 | RESULT 60초 / PROCESSING 5분 | 영구 (7일 후 정리 배치) |
| 막는 것 | 더블클릭, 네트워크 재시도 | Redis 페일오버·스플릿브레인·크래시 |
| 프런트 협조 | 불필요 | 필요 |

두 키가 서로 다른 질문에 답한다. 카트 해시는 "지금 이 순간 같은 내용의 요청이 겹쳤나", `requestId` 는
"이건 아까 그 요청과 같은 요청인가". 앞엣것은 시간에 의존해야 말이 되고, 뒤엣것은 시간과 무관해야 말이 된다.
그래서 각각 Redis와 DB에 맞는다.

이 조합의 이점:

- **UX 회귀가 없다.** "같은 카트로 한 잔 더"는 새 `requestId` 라 DB가 막지 않는다. 층 1의 RESULT TTL 60초만
  지나면 지금과 동일하게 주문된다.
- **프런트가 `requestId` 를 잘못 구현해도 (예: 더블클릭마다 새 UUID 발급) 층 1이 그대로 남는다.**
  더블클릭 방어가 프런트 구현에 의존하게 되는 문제가 사라진다.
- 층 2가 시간과 무관해지므로 정리 배치 주기가 UX에 영향을 주지 않는다. 순수하게 디스크 문제가 된다.

## 데이터 모델

```sql
CREATE TABLE IF NOT EXISTS payment_idempotency (
    id          BIGSERIAL   PRIMARY KEY,
    member_id   BIGINT      NOT NULL,
    request_id  VARCHAR(36) NOT NULL,
    payment_id  BIGINT      NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_payment_idempotency UNIQUE (member_id, request_id)
);
```

- **UNIQUE는 `(member_id, request_id)` 복합.** `request_id` 단독이면 다른 회원이 같은 UUID를 보내
  남의 결제를 차단하는 것이 이론상 가능하다. member를 섞으면 비용 0으로 막힌다.
- **FK는 걸지 않는다.** `kafka_outbox` 선례를 따른다. 회원 탈퇴 익명화 정책, 정리 배치 순서와 얽히지 않게 한다.
- `payment_id` 는 감사·디버깅용 기록이자, 훗날 "이전 결제 반환"으로 확장할 때의 연결고리다.
- `ddl-auto: update` (dev/prod) 이므로 엔티티 추가만으로 테이블이 생성된다.
  `schema.sql` 은 `kafka_outbox` 와 동일하게 문서용으로 함께 갱신한다 (컬럼 COMMENT 포함).

**배치 위치**: `com.whale.order.global.idempotency` — 층 1 `IdempotencyService` 바로 옆.

- `PaymentIdempotency` (엔티티) — 정적 팩토리 `of(memberId, requestId, paymentId)`, `createdAt` 은 생성자에서 채움.
- `PaymentIdempotencyRepository` — `deleteByCreatedAtBefore(LocalDateTime)` 하나 추가.

## pay() 흐름 변경

`PaymentService.pay()` 성공 분기에서 `orderHistoryRepository.save(...)` 다음,
`kafkaOutboxService.enqueue(...)` **바로 앞**에 삽입한다.

```java
paymentIdempotencyRepository.saveAndFlush(
        PaymentIdempotency.of(memberId, request.requestId(), payment.getPaymentId()));
```

`saveAndFlush` 로 UNIQUE 위반을 그 자리에서 터뜨린다. 커밋까지 미루면 불필요한 outbox row를 만들고 나서
롤백하게 된다.

위반 처리는 기존 `catch (Exception e)` **앞에** 새 catch 절을 둔다.

```java
} catch (DataIntegrityViolationException e) {
    idempotencyService.delete(key);
    log.warn("[결제] 층2 멱등성 충돌 memberId={} requestId={}", memberId, request.requestId());
    incrementIdempotencyConflict("db");
    throw new DuplicateRequestException("이미 처리된 결제 요청입니다. 주문 내역을 확인해주세요.");
}
```

- 제약 위반으로 트랜잭션은 이미 rollback-only다. 예외 타입만 바꿔도 롤백은 그대로 일어나
  주문·결제·PaymentHistory·outbox가 통째로 사라진다 — 의도한 동작이다.
- 타입 변환이 필요한 이유: `DataIntegrityViolationException` 은 `GlobalExceptionHandler` 에 핸들러가 없어
  그대로 두면 500이 나간다. `DuplicateRequestException` 은 기존 409 경로를 탄다.
- `idempotencyService.delete(key)` 로 층 1 키를 지워도 안전하다. 재시도가 들어와도 층 2가 다시 막는다.

**메트릭**: `payment.idempotency.conflict` 카운터에 `layer` 태그(`redis` | `db`)를 붙여 두 층 모두 계측한다.
"Redis가 뚫려서 DB가 막은 횟수"가 이 작업의 관측 산출물이다. 층 1 충돌 지점은
`if (!idempotencyService.markProcessing(key))` 분기의 `DuplicateRequestException` throw 직전.

`incrementIdempotencyConflict(String layer)` 는 새로 추가하는 private 헬퍼다. 기존 `payment.processed`
카운터가 `Counter.builder(...).register(meterRegistry).increment()` 형태로 인라인 작성돼 있으므로
같은 방식을 따르되, 두 곳에서 쓰이므로 메서드로 묶는다.

## 요청 계약

`PaymentRequest` 에 `requestId` 필드를 추가한다. `expectedAmount` 와 동일하게 **필수**다.

```java
@NotBlank(message = "요청 식별자가 누락되었습니다")
@Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "요청 식별자 형식이 올바르지 않습니다")
String requestId,
```

길이를 36자로 고정해 임의 길이 문자열이 DB 컬럼까지 도달하지 않게 한다.

**프런트** (`frontend/src/pages/customer/PaymentPage.jsx`)

```js
// 결제 시도 단위 멱등성 키 — 마운트 시 1회 생성, 재시도 시 그대로 재사용.
// 성공하면 주문 상세로 이동하며 언마운트되므로 다음 결제는 새 값이 발급된다.
const [requestId] = useState(() => newRequestId())
```

- `crypto.randomUUID()` 는 보안 컨텍스트(https/localhost)에서만 존재한다. 평문 HTTP 배포에서 `undefined` 가
  되므로 폴백 구현을 함께 둔다.
- 결제 실패 후 재시도는 같은 `requestId` 를 재사용하지만, **성공 시에만 INSERT** 하므로 막히지 않는다.

**k6 스크립트** — `requestId` 가 필수이므로 없으면 400이다. 4개 파일 모두 갱신 대상.

- `k6/idempotency-test.js` — 중복 요청 검증이 목적이므로 **동일한** `requestId` 를 재사용하도록 수정.
- `k6/order-load-test.js`, `k6/stock-concurrency-test.js` — 요청마다 **새** UUID 생성 (정상 주문 흐름).

## 층 7 — TTL 조정

`IdempotencyService` 상수를 변경한다.

- `PROCESSING_TTL` 60초 → **5분**
- `RESULT_TTL` 60초 **유지**

지금까지 PROCESSING을 짧게 둔 이유는 "만료되지 않으면 죽은 키가 결제를 무한 차단한다"였다. 이제 만료 후
재시도가 들어와도 층 2가 막으므로, 만료를 서둘러야 할 이유가 줄었다. 대가는 실제 데드락 시 최대 5분 대기인데
Mock PG는 ms 단위 응답이라 실질 영향이 없다.

`RESULT_TTL` 을 건드리면 "1분 뒤 같은 카트 재주문" UX가 바뀌므로 그대로 둔다.

클래스 Javadoc의 TTL 정책 설명도 함께 갱신한다 (현재 "PROCESSING 60초" 로 명시돼 있음).

## Mock 결제 성공률 설정값 분리

원래 범위 밖이지만 테스트를 위해 필요하다. 현재 `ThreadLocalRandom.current().nextInt(100) < 90` 이 하드코딩돼
있어 성공 경로를 결정적으로 테스트할 수 없다. 층 2 테스트가 10% 확률로 깨진다.

```java
@Value("${payment.mock.success-rate:90}")
private int mockSuccessRate;
```

- `application.yaml` 기본값 90 유지 (동작 변화 없음).
- `application-test.yaml` 에서 100으로 고정.

## 테스트

기존 테스트는 `@SpringBootTest` + `@ActiveProfiles("test")` + `TestContainerBase` 상속 형태다
(Testcontainers로 실제 PostgreSQL·Redis, Kafka는 EmbeddedKafka). 실제 PostgreSQL이므로 UNIQUE 제약이
그대로 강제된다. `KafkaOutboxRepositoryTest` / `KafkaOutboxCleanupJobTest` 를 템플릿으로 삼는다.

- **`PaymentIdempotencyRepositoryTest`** — 같은 `(memberId, requestId)` 2회 저장 시 `DataIntegrityViolationException`.
  다른 `memberId` + 같은 `requestId` 는 정상 저장 (복합 UNIQUE 검증).
- **`PaymentServiceIdempotencyTest`** — 층 1을 우회한 상태에서 같은 `requestId` 로
  `pay()` 를 2회 호출 → 두 번째는 `DuplicateRequestException`, `orders` 는 1건만 존재.
  층 1 우회는 `IdempotencyService` 를 `@MockitoBean` 으로 대체해 `markProcessing` 이 항상 `true` 를
  반환하게 만든다 (= Redis 페일오버 시뮬레이션). Spring Boot 3.5.14라 `@MockitoBean` 을 쓴다
  (`@MockBean` 은 3.4부터 deprecated). 현재 테스트 코드에 목 사용 선례는 없다.
  `pay()` 는 장바구니가 비어 있으면 즉시 예외이므로, 두 호출 사이에 카트가 유지되도록
  `OrderCreatedEvent` AFTER_COMMIT 리스너의 `clearCart` 영향을 확인하고 필요하면 카트를 다시 채운다.
- **`IdempotencyServiceTest`** — TTL 상수 변경이 기존 단언을 깨지 않는지 확인. 현재 60초 값을 직접 단언하는
  코드는 없으므로 수정 불필요할 가능성이 높다.
- **`k6/idempotency-test.js`** — 동일 `requestId` 동시 요청 시 1건만 성공.

## 정리 배치

`PaymentIdempotencyCleanupJob` — `KafkaOutboxCleanupJob` 과 동일한 형태.

```java
private static final int RETENTION_DAYS = 7;

@Scheduled(cron = "0 30 3 * * *")   // outbox 청소(3:00)와 겹치지 않게 3:30
@Transactional
public void cleanup() {
    LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
    long deleted = paymentIdempotencyRepository.deleteByCreatedAtBefore(cutoff);
    log.info("[멱등성] 청소 완료 deleted={} cutoff={}", deleted, cutoff);
}
```

테스트는 `KafkaOutboxCleanupJobTest` 와 동일한 형태로 `PaymentIdempotencyCleanupJobTest` 를 추가한다
(보존 기간 이전/이후 row를 심고 `cleanup()` 호출 후 잔존 여부 확인).

보존 기간이 UX와 무관해졌으므로 7일은 순수하게 "재시도가 들어올 수 있는 현실적 기간 + 디버깅 여유"다.

**배치를 아예 두지 않는 선택지도 검토했다.** row가 60바이트 남짓이고 `payment` 테이블과 1:1로 늘어나는데,
정작 `payment` 는 영구 보존하므로 이것만 지울 이유가 약하다 (payload가 TEXT인 `kafka_outbox` 와는 사정이 다르다).
그럼에도 넣어두기로 했다 — 나중에 규모가 커졌을 때 배치를 새로 설계하는 것보다, 있는 것을 조정하는 편이 싸다.

## 영향 파일

**신규**

- `src/main/java/com/whale/order/global/idempotency/PaymentIdempotency.java`
- `src/main/java/com/whale/order/global/idempotency/PaymentIdempotencyRepository.java`
- `src/main/java/com/whale/order/global/idempotency/PaymentIdempotencyCleanupJob.java`
- `src/test/java/com/whale/order/global/idempotency/PaymentIdempotencyRepositoryTest.java`
- `src/test/java/com/whale/order/global/idempotency/PaymentIdempotencyCleanupJobTest.java`
- `src/test/java/com/whale/order/domain/payment/service/PaymentServiceIdempotencyTest.java`

**수정**

- `src/main/java/com/whale/order/domain/payment/dto/PaymentRequest.java`
- `src/main/java/com/whale/order/domain/payment/service/PaymentService.java`
- `src/main/java/com/whale/order/global/idempotency/IdempotencyService.java`
- `src/main/resources/db/schema.sql`
- `src/main/resources/application.yaml`, `src/test/resources/application-test.yaml`
- `frontend/src/pages/customer/PaymentPage.jsx`
- `k6/idempotency-test.js`, `k6/order-load-test.js`, `k6/stock-concurrency-test.js`
- `docs/wiki/architecture/(추가필요)멱등redis에 추가할것.md`, `docs/wiki/api/rest-api.md`,
  `docs/wiki/architecture/redis-usage.md`, `docs/wiki/conversations/2026-08-20.md`

## 리스크

- **프런트/백엔드 동시 배포 필요.** `requestId` 가 필수라 구버전 프런트는 400을 받는다.
  `deploy.yml` 이 백엔드·프런트 이미지를 함께 빌드·푸시하므로 배포 단위는 어차피 하나다.
- **동시 요청 둘이 모두 결제 처리를 끝낸 뒤 커밋 시점에 충돌한다.** 층 2는 "늦게 막는" 방어다.
  Mock PG는 실제 청구가 없어 무해하지만, 실 PG 이관 시에는 층 3(PG Idempotency-Key)이 반드시 필요하다.
  이 한계는 원본 문서의 층 3 항목으로 이미 기록돼 있다.
- **`ddl-auto: update` 는 기존 배포 DB에 UNIQUE 제약을 추가하지 못할 수 있다.** 신규 테이블이라 이번엔
  해당되지 않지만, 배포 후 `payment_idempotency` 테이블과 `uk_payment_idempotency` 제약이 실제로
  생성됐는지 확인이 필요하다.
