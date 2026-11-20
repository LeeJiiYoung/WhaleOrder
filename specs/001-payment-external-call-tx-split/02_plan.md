# Plan: 기능 001 결제 승인 외부 호출과 DB 트랜잭션 분리

상태: 검토 완료 (2026-11-20)
작성일: 2026-11-20
입력: 01_spec.md (FR-001~016, SC-001~008), docs/constitution-draft.md v0.3.0


## 0. 검토에서 확정한 결정

- 결제 상태 전이는 짧은 트랜잭션 안의 비관적 락 + 엔티티 메서드로 한다 (3장). 조건부 UPDATE는 쓰지 않는다.
- 흐름(PaymentConfirmFacade)과 트랜잭션(PaymentApprovalTxService)을 다른 빈으로 나눈다 (4-1).
- 토스 confirm에 Idempotency-Key 헤더를 붙인다 (4-4).
- 확인 중은 HTTP 202로 응답한다 (4-3).
- Payment에 approving_started_at, review_reason 컬럼을 추가한다 (2-4). approving_started_at은 정리·늦은 확정 기준점이자 관리자 화면 표시용, review_reason은 관리자가 재조회할지 토스 대시보드를 볼지 판단하는 용도다.
- 토스 5xx는 승인 거절이 아니라 결과 불명으로 보고 APPROVING을 유지한다 (4-2). 고객은 5xx 시 실패 화면 대신 확인 중 화면을 본다.


## 1. 현재 구조와 문제 지점

PaymentService.confirm()은 하나의 @Transactional(noRollbackFor = PaymentFailedException) 안에서 아래를 모두 한다.
1. Redis 멱등 키 확인 (markProcessing)
2. 주문·결제 조회, 본인 확인, 상태 확인(SUCCESS면 그대로 반환, PENDING 아니면 거부), 금액 대조
3. tossPaymentClient.confirm() 호출
4. 성공: payment.success(), order.confirmPayment(), 이력 저장, Outbox enqueue, OrderCreatedEvent 발행
   실패(RestClientResponseException): payment.fail(), order.cancelUnpaid(), PaymentFailedException

코드를 보며 추가로 확인한 문제
- 결제 행을 락 없이 읽는다. Redis 키가 사라지면 두 요청이 모두 PENDING을 보고 토스를 두 번 호출할 수 있다.
- 타임아웃·네트워크 오류(ResourceAccessException)는 catch(Exception)으로 빠져 트랜잭션이 롤백된다. 결제는 PENDING, 주문은 AWAITING_PAYMENT로 남고, 30분 뒤 PaymentSweepScheduler가 주문을 취소한다. 토스에서는 승인됐는데 우리는 취소로 기록하는 경로가 이미 존재한다.
- 토스 5xx도 승인 거절(4xx)과 똑같이 실패로 처리한다. 5xx는 토스 쪽 처리 결과를 알 수 없는 상태다.
- TossPaymentClient에 타임아웃이 없다(SimpleClientHttpRequestFactory 기본값 = 무제한).


## 2. 상태 설계

### 2-1. PaymentStatus 추가
- APPROVING: 승인 진행 중. 토스 승인 호출 전에 커밋되는 상태.
- NEEDS_REVIEW: 확인 필요. 정리 작업으로도 결과를 확정하지 못한 상태.

status 컬럼은 VARCHAR(20)이라 길이는 충분하다.

### 2-2. 결제 상태 전이

PENDING ──(confirm 시작)──> APPROVING
APPROVING ──(토스 승인 성공)──> SUCCESS
APPROVING ──(토스 4xx 거절)──> FAILED
APPROVING ──(타임아웃·5xx·네트워크 오류)──> APPROVING 유지
APPROVING ──(정리 작업: 토스 DONE)──> SUCCESS
APPROVING ──(정리 작업: 토스 미승인)──> FAILED
APPROVING ──(정리 작업: 조회 실패 또는 예상 밖 상태)──> NEEDS_REVIEW
NEEDS_REVIEW ──(관리자 재조회)──> SUCCESS / FAILED / NEEDS_REVIEW 유지
SUCCESS ──(늦은 확정 자동 환불 성공)──> CANCELLED
SUCCESS ──(늦은 확정 자동 환불 실패)──> NEEDS_REVIEW (사유: 환불 실패)

허용되지 않는 전이는 엔티티 메서드에서 IllegalStateException을 던진다.

### 2-3. 주문 상태
- APPROVING, NEEDS_REVIEW 동안 주문은 AWAITING_PAYMENT를 유지한다 (매장 화면 비노출).
- 결제 SUCCESS 확정 시 AWAITING_PAYMENT → PENDING (기존 confirmPayment).
- 결제 FAILED 확정 시 AWAITING_PAYMENT → CANCELLED (기존 cancelUnpaid).
- 늦은 확정(FR-016) 시 AWAITING_PAYMENT → CANCELLED. 기존 cancelUnpaid를 쓰고 이력 사유로 구분한다.

### 2-4. Payment 엔티티 변경
- 컬럼 추가
  - approving_started_at TIMESTAMP: APPROVING으로 바뀐 시각. 정리 기준(5분)과 늦은 확정 기준(30분)의 기준점.
  - review_reason TEXT: NEEDS_REVIEW 사유. failed_reason과 분리한다 (실패 사유와 확인 필요 사유는 의미가 다르다).
- externalTxId(기존 컬럼)에 APPROVING 전환 시점부터 paymentKey를 저장한다 (FR-009). 지금은 SUCCESS 시점에만 저장한다.
- 도메인 메서드 추가: startApproving(paymentKey, now), approve(paymentKey, method), rejectApproval(reason), markNeedsReview(reason), 기존 success/fail/cancel의 허용 전이 검증 강화.


## 3. 동시성 방어 (FR-003)

### 결정: 짧은 트랜잭션 안의 비관적 락 + 엔티티 메서드
- PaymentRepository에 findByIdForUpdate / findByOrdersForUpdate 추가 (@Lock(PESSIMISTIC_WRITE), 락 대기 타임아웃 힌트 3초).
- 모든 상태 전이 트랜잭션은 결제 행을 먼저 락으로 읽고, 엔티티 메서드로 상태를 바꾼다.

조건부 UPDATE를 쓰지 않는 이유
- 현재 코드는 조건부 UPDATE를 쓰지 않는다. 결제 상태는 모두 엔티티 메서드(payment.success(), fail(), cancel())와 더티 체킹으로 바뀐다.
- 조건부 UPDATE(JPQL @Modifying "update ... where status = 'PENDING'")를 도입하면 엔티티를 거치지 않고 DB를 직접 바꾸게 된다. 그 결과
  - 상태 전이 규칙이 엔티티 메서드와 쿼리 두 곳으로 흩어진다.
  - 같은 트랜잭션에서 이미 읽은 엔티티와 DB 값이 어긋난다(clearAutomatically 등으로 따로 관리해야 한다).
  - JPA Auditing(BaseEntity의 updated_at 등)이 적용되지 않는다.
- 이는 Constitution 원칙 VI(상태는 의미 있는 도메인 메서드로만 바꾼다)와 맞지 않는다.
- 이전에 비관적 락을 피하려 했던 이유는 "외부 호출 중에 락을 잡는다"였다. 이번 설계에서는 토스 호출이 트랜잭션 밖이라 락은 수 ms 동안만 잡힌다. 이 조건에서는 비관적 락이 단순하고 안전하다.

락 적용 범위
- 결제 상태를 바꾸는 트랜잭션에만 락을 쓴다: startApproval, completeApproval, rejectApproval, markNeedsReview, 늦은 확정 처리, doCancelAwaitingPayment.
- 상태를 바꾸지 않는 조회(고객 결제 정보, 관리자 목록, 정리 대상 조회)는 락 없이 읽는다.
- 토스 호출 중에는 어떤 락도 잡고 있지 않는다.

락 순서 규칙 (데드락 방지)
- 결제와 주문을 함께 바꾸는 모든 트랜잭션은 결제 행을 먼저 락으로 잡고 그다음 주문을 바꾼다.
- 결제 대기 정리(doCancelAwaitingPayment)도 같은 순서로 바꾼다 (5장).


## 4. 트랜잭션 분리 구조 (FR-001, 002, 007, 008, 011)

### 4-1. 클래스 구성
- PaymentConfirmFacade (신규, @Transactional 없음): confirm 흐름 조립. Controller는 이 클래스를 호출한다.
- PaymentApprovalTxService (신규): 상태 전이 트랜잭션 메서드 모음. 각 메서드가 독립된 @Transactional이다.
  - 같은 클래스 안에서 @Transactional 메서드를 호출하면 프록시를 거치지 않아 트랜잭션이 적용되지 않는다. 그래서 흐름(Facade)과 트랜잭션(TxService)을 다른 빈으로 나눈다.
- PaymentService.confirm()은 제거하고 관련 private 헬퍼(parseTossOrderId, resolveMethod, savePaymentHistory 등)는 TxService로 옮기거나 공유한다.

### 4-2. confirm 흐름
1. [트랜잭션 밖] Redis 멱등 키 확인 (기존 로직 유지, 1차 방어)
2. [TX1 startApproval] 결제 행 락 → 본인 확인 → 상태별 분기
   - SUCCESS: 기존 결과 반환 (현재 동작과 동일)
   - APPROVING / NEEDS_REVIEW: "확인 중" 반환
   - PENDING이 아닌 그 밖의 상태: 거부
   - PENDING: 금액 대조 → startApproving(paymentKey, now) → 이력(APPROVING) 저장 → 커밋
3. [트랜잭션 밖] 토스 승인 호출 (Idempotency-Key 헤더 포함, 4-4)
4. 결과별 처리
   - 성공 → [TX2 completeApproval] 결제 행 락 → APPROVING일 때만 approve() → order.confirmPayment() → 이력 → Outbox enqueue → OrderCreatedEvent 발행 → 커밋. 이미 다른 경로(정리 작업)가 확정했으면 아무것도 하지 않는다.
   - 4xx(승인 거절) → [TX2 rejectApproval] APPROVING일 때만 rejectApproval() → order.cancelUnpaid() → 이력 → 커밋 → PaymentFailedException
   - 5xx, 타임아웃, 네트워크 오류 → 상태를 바꾸지 않는다(APPROVING 유지). 로그 + 메트릭 후 "확인 중" 반환. 자동 재시도 없음(FR-011).
   - TX2 자체가 실패(예: DB 오류) → APPROVING으로 남는다. 정리 작업이 확정한다(SC-002).
5. [트랜잭션 밖] Redis: 확정 결과면 saveResult, "확인 중"이면 delete (캐시하지 않는다. 확인 중 응답이 60초간 캐시되면 그 사이 확정돼도 계속 확인 중을 돌려주게 된다).

### 4-3. 응답 계약
- 성공: 200, 기존 PaymentResponse
- 실패: 기존과 동일(PaymentFailedException → GlobalExceptionHandler)
- 확인 중: 202 Accepted, ApiResponse.ok("결제를 확인하고 있어요", PaymentResponse(status=APPROVING, orderId 포함))
- 결정(2026-11-20): 확인 중은 202로 응답한다. 200 + body status로 구분하는 방식보다 "접수됐지만 처리 미완료"라는 의미가 정확하다.

### 4-4. TossPaymentClient 변경 (FR-010)
- SimpleClientHttpRequestFactory에 연결 타임아웃 5초, 응답 타임아웃 30초 설정. 값은 application.yaml(toss.connect-timeout, toss.read-timeout)로 뺀다.
- confirm 요청에 Idempotency-Key 헤더 추가. 값: "whale-confirm-{orderId}". 토스는 같은 키의 요청에 같은 응답을 주고 키는 15일간 유효하다. 우리 쪽 Redis·DB 방어가 모두 뚫려도 토스가 중복 승인을 막는 3차 방어선이다.
- 결제 조회 메서드 추가: getPayment(paymentKey) → GET /v1/payments/{paymentKey}, 응답 record TossPaymentResponse(paymentKey, orderId, status, totalAmount, method, easyPay).


## 5. 결제 대기 정리와의 충돌 해소 (FR-015)

- PaymentService.doCancelAwaitingPayment()에서 주문 상태 확인 전에 결제 행을 락으로 읽고, 결제가 PENDING이 아니면(APPROVING, NEEDS_REVIEW 포함) 건너뛴다.
- Redis confirm 키로 confirm과 충돌을 피하던 기존 장치는 1차 방어로 남겨 둔다.
- 영향 경로: PaymentSweepScheduler(30분), PaymentController.cancelPending(고객 결제창 이탈).


## 6. 정리 작업 (FR-004, 005, 016)

### 6-1. PaymentApprovalReconciler (신규)
- @Scheduled(fixedDelay = 60초). 대상: status = APPROVING AND approving_started_at < now - 5분. 한 번에 최대 50건.
- 건별로 독립 처리한다. 한 건의 예외가 다른 건을 막지 않는다 (예외는 해당 건을 NEEDS_REVIEW로 기록, Constitution IV).
- 관리자 재조회(7장)도 같은 reconcile(paymentId) 메서드를 쓴다. 대상 상태만 NEEDS_REVIEW로 다르다.

### 6-2. reconcile(paymentId) 흐름
1. [트랜잭션 밖] 토스 getPayment(paymentKey)
2. 토스 상태별 처리
   - DONE, 승인 시작 후 30분 이내 → [TX] completeApproval (4-2와 같은 메서드)
   - DONE, 승인 시작 후 30분 초과 → 늦은 확정 처리 (6-3)
   - 승인되지 않은 상태(IN_PROGRESS, READY, ABORTED, EXPIRED) → [TX] rejectApproval. 우리 서버는 같은 결제를 다시 승인 호출하지 않으므로 토스 승인 유효 시간(10분)이 지나면 만료된다.
   - 그 밖의 상태(CANCELED, PARTIAL_CANCELED, WAITING_FOR_DEPOSIT 등) → [TX] markNeedsReview("예상 밖 토스 상태: X")
   - 조회 실패(타임아웃, 4xx, 5xx) → [TX] markNeedsReview("토스 조회 실패: ...")
   - 토스 상태 이름은 V-1에서 실제 응답으로 확인한 뒤 확정한다.

### 6-3. 늦은 확정 처리 (FR-016)
1. [TX] 결제 행 락 → approve() (SUCCESS 기록, 실제로 돈이 나갔다는 사실) → order.cancelUnpaid() → 이력(사유: 결제 확인 지연) → 커밋
2. PaymentService.cancelPayment(order, "결제 확인 지연 자동 환불") 호출 (기존 메서드, 트랜잭션 안에서 토스 취소 호출 — Constitution 원칙 X 임시 예외)
3. 2가 실패하면 [TX] SUCCESS → markNeedsReview("환불 실패: ...")
4. NEEDS_REVIEW(환불 실패) 건을 관리자가 재조회하면: 토스 DONE이고 주문이 이미 CANCELLED면 2부터 다시 시도. 토스 CANCELED면 결제를 CANCELLED로 맞춘다.

### 6-4. 관측 (Constitution IV-1 규칙 4 준용)
- Counter payment.approval.unknown{reason=timeout|5xx|io}: confirm에서 결과를 모른 채 끝난 횟수
- Counter payment.approval.reconciled{result=success|failed|review|late_refund}
- Gauge payment.needs_review.count: NEEDS_REVIEW 건수 (Grafana 패널 추가)


## 7. 관리자 화면 (FR-013, 014)

### 백엔드
- AdminPaymentReviewController (/api/admin/payment-reviews). SecurityConfig의 "/api/admin/**" → ADMIN 규칙이 그대로 적용된다.
  - GET  /api/admin/payment-reviews: NEEDS_REVIEW 목록. 응답 record PaymentReviewResponse(paymentId, orderId, storeName, memberName, amount, paymentKey, approvingStartedAt, reviewReason)
  - POST /api/admin/payment-reviews/{paymentId}/recheck: reconcile 실행 후 결과 상태 반환
- 재조회 실행자와 시각은 PaymentHistory에 남긴다. PaymentHistory에 실행자 컬럼이 없으므로 reason에 "관리자 재조회(memberId=N)"를 기록하고 시각은 changedAt을 쓴다.

### 프론트엔드
- pages/admin/PaymentReviewPage.jsx (+ module.css): StockRestoreFailurePage 구조를 따른다. 목록 + 행별 "토스 재조회" 버튼.
- api/adminPayment.js 추가, App.jsx 라우트 /admin/payment-reviews 추가(AdminRoute), AdminLayout 메뉴 추가.


## 8. 고객 화면 (FR-008, 012)

- PaymentSuccessPage.jsx
  - confirmPayment 호출에 axios timeout 10000 지정.
  - 200: 기존 완료 화면
  - 202 또는 timeout(ECONNABORTED): orderId("whale-17" → 17)를 파싱해 /orders/{orderId}로 이동
  - 그 밖의 오류: 기존 실패 화면
- OrderDetailPage.jsx
  - 결제 상태가 APPROVING 또는 NEEDS_REVIEW면 "결제를 확인하고 있어요" 배너 표시.
  - 3초마다 getOrder + getPaymentByOrder 재조회. 결제 상태가 바뀌면 중단. 10분이 지나면 중단하고 "고객센터 문의" 안내.
- PaymentInfoResponse.statusLabel: APPROVING, NEEDS_REVIEW 모두 "결제 확인 중". 고객에게는 내부 상태 차이를 노출하지 않는다.


## 9. DB 변경

dev/prod는 ddl-auto: update라 컬럼은 자동 추가되지만, 인덱스와 체크 제약은 자동으로 바뀌지 않는다. 아래를 수동으로 실행한다. schema.sql(문서용)과 컬럼 COMMENT도 함께 갱신한다.

ALTER TABLE payment ADD COLUMN IF NOT EXISTS approving_started_at TIMESTAMP;
ALTER TABLE payment ADD COLUMN IF NOT EXISTS review_reason TEXT;
CREATE INDEX IF NOT EXISTS idx_payment_status_approving ON payment (status, approving_started_at);

V-2 결과에 따라 추가
- Hibernate 6은 @Enumerated(STRING) 컬럼으로 테이블을 만들 때 허용 값 목록 CHECK 제약을 생성할 수 있다. dev/prod 테이블이 Hibernate로 생성됐다면 payment.status, payment_history.status에 기존 4개 값만 허용하는 제약이 있을 수 있고, 그러면 APPROVING 저장 시 실패한다.
- 제약이 있으면 DROP 후 새 값 목록으로 다시 만든다.

테스트 프로필은 ddl-auto: create-drop이라 영향 없다.


## 10. 사전 검증 (구현 전)

- V-1. 토스 결제 조회 API 동작 확인: 테스트 키로 결제 1건을 승인한 뒤 GET /v1/payments/{paymentKey} 호출. 응답 status 값과, 승인하지 않고 둔 결제의 status 값(10분 전후)을 기록한다. 6-2의 상태 분기를 이 결과로 확정한다.
- V-2. dev/prod DB에서 \d payment, \d payment_history로 status CHECK 제약 존재 여부 확인.
- V-3. Idempotency-Key 헤더를 붙인 confirm이 테스트 키에서 정상 동작하는지 확인.


## 11. 테스트 계획 (Constitution VII)

모든 테스트는 구현 전에 먼저 작성한다(VII-1).

### 단위 (스프링 없음)
- PaymentTest: 허용 전이만 성공하고 나머지는 IllegalStateException. startApproving이 paymentKey와 시각을 기록하는지.
- 선행 작업: StockLockFacadeTest(Mockito) — tryLock이 InterruptedException을 던지면 StockLockException, 인터럽트 플래그 유지.

### Mockito
- PaymentConfirmFacadeTest: 토스 성공 / 4xx / 5xx / 타임아웃 / TX2 실패 각각에서 호출되는 TxService 메서드와 응답 종류.
- PaymentApprovalReconcilerTest: 토스 상태별 분기(6-2), 30분 경계(29분 59초 / 30분 1초), 조회 실패 → NEEDS_REVIEW, 한 건 예외가 다음 건을 막지 않음.

### 통합 (@SpringBootTest + TestContainerBase, TossPaymentClient는 @MockitoBean)
- SC-001 ConfirmConcurrencyTest: IdempotencyService.markProcessing을 항상 true로 스파이, 같은 주문 confirm 20회 동시 → 토스 confirm 호출 1회, 결제 SUCCESS 1건.
- SC-002 ApprovalRecoveryTest: TX2(completeApproval)를 강제로 실패시킴 → APPROVING 잔류 확인 → approving_started_at을 6분 전으로 조정 → 정리 작업 실행(토스 DONE) → 결제 SUCCESS, 주문 PENDING, Outbox 1건.
- SC-003 ConfirmConnectionTest: Hikari 최대 풀 2로 설정, 토스 응답 5초 지연인 confirm 3건 진행 중에 다른 주문 confirm(지연 없음)이 1초 안에 끝나는지.
- SC-004: 기존 테스트 전체 통과 (SagaCompensationTest, IdempotencyServiceTest 등). 기존 테스트의 기대값은 바꾸지 않는다(VII-4). 구조 변경으로 컴파일이 깨지는 테스트는 호출 대상만 PaymentConfirmFacade로 바꾼다.
- SC-006 PaymentReviewTest: 토스 조회 실패 → NEEDS_REVIEW → 관리자 GET 목록에 노출 → 토스 DONE으로 바꾸고 POST recheck → SUCCESS.
- SC-007 SweepSkipTest: NEEDS_REVIEW 결제의 주문을 31분 전 생성으로 조정 → PaymentSweepScheduler 실행 → 주문 AWAITING_PAYMENT 유지.
- SC-008 LateApprovalTest: approving_started_at 31분 전, 토스 DONE → 주문 CANCELLED, 토스 cancel 1회 호출, 결제 CANCELLED. 토스 cancel 실패 시 NEEDS_REVIEW(환불 실패).

### 수동 확인
- SC-005: 프론트 테스트 도구가 없으므로 수동 확인. TossPaymentClient를 로컬에서 30초 지연시키는 dev 전용 설정으로 화면 이동과 주문 상세 갱신을 확인한다.


## 12. Constitution 게이트

- I 재고 변경: 해당 없음 (재고 로직 변경 없음. 선행 작업은 I의 예외 분류 개선)
- II 결제 멱등성: 충족. Redis 1차 + 결제 행 락 2차 + 토스 Idempotency-Key 3차
- III Outbox: 충족. completeApproval 트랜잭션 안에서 enqueue (FR-007)
- IV 실패 분류: 충족. 결과 불명은 재시도하지 않고 APPROVING 유지, 확정 불가는 NEEDS_REVIEW로 DB 기록 + 관리자 화면 + 메트릭
- V Kafka off: 충족. Outbox enqueue는 Kafka 설정과 무관. 기존 동작 유지
- VI 계층과 모델: 충족. 상태 전이는 엔티티 메서드, 신규 DTO는 record, 관리자 API는 /api/admin
- VII 테스트: 충족. 11장
- VIII 문서: 구현 후 wiki domains/payment.md, architecture/saga-compensation.md 갱신, Home에 링크
- IX 사람 승인: DB 변경(9장)과 배포는 사람이 실행
- X 외부 호출: confirm과 정리 작업 충족. 늦은 확정 환불(6-3)만 임시 예외(Constitution 기록됨)


## 13. 변경 파일 목록

백엔드 (신규)
- domain/payment/service/PaymentConfirmFacade.java
- domain/payment/service/PaymentApprovalTxService.java
- domain/payment/service/PaymentApprovalReconciler.java
- domain/payment/controller/AdminPaymentReviewController.java
- domain/payment/dto/PaymentReviewResponse.java
- domain/payment/client/TossPaymentResponse.java

백엔드 (수정)
- domain/payment/entity/PaymentStatus.java, Payment.java
- domain/payment/repository/PaymentRepository.java (락 조회, 정리 대상 조회, NEEDS_REVIEW 목록)
- domain/payment/client/TossPaymentClient.java
- domain/payment/service/PaymentService.java (confirm 제거, doCancelAwaitingPayment 수정)
- domain/payment/controller/PaymentController.java (Facade 호출, 202 응답)
- domain/payment/dto/PaymentInfoResponse.java (상태 라벨)
- domain/stock/service/StockLockFacade.java (선행 작업)
- resources/application.yaml (toss 타임아웃), resources/db/schema.sql (문서)

프론트엔드
- pages/customer/PaymentSuccessPage.jsx, pages/customer/OrderDetailPage.jsx
- pages/admin/PaymentReviewPage.jsx(+css) 신규, api/adminPayment.js 신규, App.jsx, components/admin/AdminLayout.jsx

모니터링
- Grafana 대시보드에 payment.needs_review.count, payment.approval.unknown 패널 추가


## 14. 위험과 확인 필요

- 토스 상태 이름과 의미는 V-1 전까지 가정이다.
- 기존 동작 변경: 토스 5xx를 실패에서 "결과 불명"으로 바꾼다. 5xx 응답 후 고객은 즉시 실패 화면 대신 확인 중 화면을 보게 된다.
- confirm 구조가 바뀌어 기존 통합 테스트 일부가 PaymentService.confirm을 직접 호출한다면 호출 대상 수정이 필요하다(기대값은 유지).
- 단일 서버 가정: 정리 스케줄러가 여러 인스턴스에서 동시에 돌아도 결제 행 락과 상태 확인으로 중복 확정은 막히지만, 토스 조회는 중복 호출될 수 있다. 다중 서버 전환 시 ShedLock 등을 검토한다.
