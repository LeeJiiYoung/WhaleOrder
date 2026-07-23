# 결제 멱등성 — Redis 단일 방어의 한계

> **상태: 현재 구조 = Redis 단일 방어 (IdempotencyService)** — 데모/포트폴리오엔 충분하지만 실서비스 이관 시 위험 지점 정리. 개선안은 층별로 나눠 기록.

## 현재 구현

**관련 코드**
- `src/main/java/com/whale/order/global/idempotency/IdempotencyService.java`
- `src/main/java/com/whale/order/domain/payment/service/PaymentService.java` (line 121~239)

**메커니즘**
- Redis `SET NX EX` (원자적) 로 `markProcessing`
- 성공 시 결제 처리 → `saveResult(key, response)` (TTL 60초 갱신)
- 실패 시 `delete(key)` — 재시도 허용

**키 생성**
```
raw = memberId + storeId + method + orderType + customerRequest + toJson(cart)
key = SHA-256(raw)
```

**TTL**
- PROCESSING_TTL = 60초
- RESULT_TTL = 60초

## 뚫리는 시나리오 6가지

### 시나리오 1: Redis 마스터-슬레이브 페일오버 갭 ⭐ 가장 유명

Redis 는 비동기 복제라 마스터→슬레이브 사이 replication lag 이 항상 존재.

```
T=0    요청 A: markProcessing → 마스터에 키 저장 → true
T=1ms  마스터 다운 (슬레이브에 복제 전)
T=2ms  슬레이브가 마스터로 승격 (키 없음)
T=3ms  요청 A' (네트워크 재시도): markProcessing → true
       → 중복 결제 성사
```

Martin Kleppmann 이 지적한 Redis Distributed Lock 문제와 동일 계열. RedLock 알고리즘도 완벽하지 않음.

### 시나리오 2: PROCESSING_TTL 60초 만료

PG 응답이 60초 넘게 걸리면:
```
T=0     markProcessing → true, 결제 시작
T=1s    PG 호출 → 갑자기 느려짐 (PG 장애)
T=61s   TTL 만료 → 키 자동 삭제
T=62s   사용자 새로고침 → 재요청 → markProcessing → true → 새 결제
T=90s   원본 결제 응답 도착 → DB 커밋
T=91s   재시도 결제도 커밋 → 카드 2번 청구
```

### 시나리오 3: JVM 프로세스 크래시 ⭐ 심각도 최상

`catch { idempotencyService.delete(key); }` 는 정상 예외에만 작동. **JVM 크래시 시엔 catch 자체가 실행 안 됨**.

```
T=0    markProcessing → true
T=1s   PG 호출 성공 → 카드에 실제 청구
T=2s   DB 커밋 직전 JVM 크래시
       → catch 실행 안 됨, 키가 PROCESSING 상태로 남음
       → DB 엔 결제 기록 없음
T=3s   로드밸런서가 다른 인스턴스로 라우팅 → 사용자 재시도
       → DuplicateRequestException (60초 안)
T=63s  PROCESSING TTL 만료 → 재시도 가능
       → PG 상태 확인 없이 새 결제 진행 → 카드 2번 청구
```

"결제됐는데 우리 DB엔 없음" = 회계 불일치.

### 시나리오 4: saveResult 실패

```java
// PaymentService.java:210
idempotencyService.saveResult(key, response);  // Redis 순단 → 예외
return response;
```

DB 는 이미 커밋됐지만 캐시 저장 실패. `saveResult` 예외가 catch 로 잡히면 `delete(key)` → 재시도 시 새 결제 → **중복 결제**.

`saveResult` 에 재시도 로직 없음.

### 시나리오 5: 카트 JSON 직렬화 비결정성

멱등성 키에 `toJson(cart)` 포함. Jackson 은 필드 순서·null 처리·소수점 표기가 컨텍스트에 따라 미묘하게 달라질 수 있음.

같은 카트인데 JSON 문자열이 다르면 → 다른 키 → 방어 뚫림.

**방어**: Canonical JSON (필드 정렬, null 정규화) 또는 카트 해시를 서버 측에서 결정적으로 계산.

### 시나리오 6: Redis 클러스터 스플릿브레인

여러 노드로 클러스터 운영 시 네트워크 파티션이 발생하면 두 파티션이 각자 다른 상태를 가짐. 로드밸런서가 서로 다른 파티션으로 요청을 보내면 두 요청 모두 `markProcessing` 성공 → 중복 결제.

## 개선안 — Defense in Depth

돈이 걸린 결제는 Redis 하나에 의존하지 않고 다층 방어.

### 층 1: Redis 멱등성 (현재 있음)
- 정상 케이스 빠른 필터
- 대부분의 재시도(네트워크 지연, 더블 클릭) 방어
- 단, 위 시나리오들엔 취약

### 층 2: DB UNIQUE 제약 ⭐ 우선순위 최상
```sql
CREATE TABLE payment_idempotency (
    key VARCHAR(64) PRIMARY KEY,
    payment_id BIGINT,
    created_at TIMESTAMP DEFAULT NOW()
);
```
- 결제 처리 시 `INSERT` — UNIQUE 위반이면 이전 결제 조회해서 반환
- Redis 가 뚫려도 DB 레벨에서 최종 차단
- 코드 변경 최소, 효과 최대

### 층 3: PG 사의 Idempotency-Key 헤더 ⭐ 우선순위 최상
```java
kakaopayClient.approve(orderId, idempotencyKey);
```
- 국내 주요 PG(토스, 카카오페이, 아임포트) 모두 지원
- 우리 시스템이 아무리 꼬여도 PG 가 방어
- 이중 청구를 근본적으로 차단

### 층 4: PG 상태 확인 API
```java
if (redisMarkerExpired) {
    PgTransaction existing = pgClient.query(orderId);
    if (existing != null && existing.isApproved()) {
        // 이미 승인됨 → 우리 DB 만 복구
        return recoverFromPgState(existing);
    }
    proceedPayment();
}
```
- 재시도 전 PG 에 조회
- 시나리오 3(크래시 후 상태 불일치) 복구

### 층 5: Outbox 패턴
- 자세한 계획은 [Outbox 도입 계획]((추가필요)outbox패턴.md) 참조
- DB 커밋과 PG 호출을 원자적으로 묶음
- JVM 크래시 복구 자동화

### 층 6: Redis 신뢰성 강화 (보조)
- Redis Sentinel/Cluster + AOF fsync=always
- 페일오버 갭은 여전히 존재 → 다른 층 없이는 부족

### 층 7: TTL 조정
- PROCESSING_TTL 을 PG 최대 응답 시간의 3배 (예: 5분)
- 짧게 두면 만료 후 재시도 위험, 길게 두면 데드락 후 대기 시간 증가 (트레이드오프)

## 환경별 필요 방어 수준

| 환경 | 최소 방어 수준 | 이유 |
|-----|-------------|------|
| 개발 / 데모 / 포트폴리오 | 층 1 (현재) | Mock PG, 트래픽 낮음, 데이터 손실 감수 가능 |
| 소규모 실서비스 | 층 1 + 층 2 + 층 3 | 회계 불일치는 CS 폭발 유발 |
| 중대형 결제 시스템 | 전 층 | 규모가 커질수록 극단 시나리오가 실제로 발생 |

## 실서비스 이관 시 최소 체크리스트

- [ ] PG 사의 `Idempotency-Key` 헤더 사용 (층 3)
- [ ] `payment_idempotency` 테이블 UNIQUE 제약 (층 2)
- [ ] 재시도 진입 전 PG 상태 확인 로직 (층 4)
- [ ] `PROCESSING_TTL` 을 PG 최대 응답 시간의 3배 이상으로 (층 7)
- [ ] Redis 페일오버 시나리오 부하 테스트 (chaos engineering)
- [ ] 카트 JSON 직렬화 결정성 검증 (시나리오 5)
- [ ] `saveResult` 실패 시 재시도 또는 대체 저장소 (시나리오 4)

## 왜 지금 프로젝트는 층 1만으로도 괜찮은가

- **Mock PG** — 실제 돈 이동 없음, 중복 결제 = DB 로그 2개 생기는 정도
- **포트폴리오 목적** — 동시성 제어 아이디어 시연이 목표, 극단 케이스 방어 코드는 오히려 노이즈
- **트래픽 규모** — 시나리오 1~2 는 통계적으로 트래픽이 커질 때 재현 확률 상승

## 핵심 인사이트

**Redis 멱등성은 "실수/재시도를 필터링하는 첫 관문"으로는 훌륭하지만, "돈 손실을 100% 막는 최후 방어선"으로는 부적합.**

결제 도메인에서는 항상 여러 계층을 겹쳐 두고 각 계층이 서로 다른 실패 모드를 커버해야 함. 이 원칙을 **Defense in Depth (심층 방어)** 라고 함.

## 관련 대화

- 2026-07-23: Redis 멱등성 안전성 논의, 6가지 시나리오 도출, 7층 방어 정리

## 참고
- [Outbox 도입 계획]((추가필요)outbox패턴.md) — JVM 크래시 시나리오 대응
- [Redis 활용처](redis-usage.md) — 현재 Redis 사용 지점
- `PaymentService.pay()` 코드 — 실제 멱등성 처리 흐름