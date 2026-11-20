# Tasks: 기능 001 결제 승인 외부 호출과 DB 트랜잭션 분리

입력: 01_spec.md, 02_plan.md
규칙
- 테스트 작성 작업은 구현 작업보다 먼저 한다 (Constitution VII-1). 테스트는 처음에 실패(또는 컴파일 실패)해야 한다.
- 테스트 기대값은 구현에 맞춰 바꾸지 않는다 (VII-4).
- [P]: 같은 단계 안에서 다른 작업과 파일이 겹치지 않아 병렬로 진행할 수 있는 작업
- [사람]: 사람이 직접 실행하거나 승인해야 하는 작업 (Constitution IX)
- 각 작업이 끝나면 체크하고, 커밋은 사람 확인 후 한다.


## 단계 0. 사전 확인과 선행 작업

- [ ] T001 [사람] V-1 토스 결제 조회 상태값 확인. 테스트 키로 결제 1건 승인 후 GET /v1/payments/{paymentKey} 응답 status 기록. 승인하지 않은 결제도 10분 전후로 조회해 기록. 결과를 02_plan.md 6-2에 반영.
- [ ] T002 [P][사람] V-2 dev, prod DB에서 \d payment, \d payment_history 실행. status CHECK 제약 존재 여부를 02_plan.md 9장에 기록.
- [ ] T003 [P][사람] V-3 Idempotency-Key 헤더를 붙인 confirm이 테스트 키에서 정상 동작하는지 확인. 같은 키로 두 번 보냈을 때 응답 기록.
- [ ] T004 [P] 선행 테스트: StockLockFacadeTest (Mockito). tryLock이 InterruptedException을 던지면 deductStock, restoreStock 모두 StockLockException 발생 + 인터럽트 플래그 유지.
- [ ] T005 선행 구현: StockLockFacade 수정. T004 통과. constitution-draft.md 불일치 3번을 해결로 표시.


## 단계 1. 결제 도메인

- [ ] T006 테스트: PaymentTest (단위). 2-2의 허용 전이만 성공하고 나머지는 IllegalStateException. startApproving이 paymentKey(externalTxId)와 approvingStartedAt을 기록. markNeedsReview가 reviewReason을 기록.
- [ ] T007 구현: PaymentStatus에 APPROVING, NEEDS_REVIEW 추가. Payment에 approvingStartedAt, reviewReason 필드와 도메인 메서드 추가, 기존 메서드 전이 검증 강화. T006 통과.
- [ ] T008 [P] DB 변경 SQL 작성 (02_plan.md 9장) + schema.sql 문서 갱신(컬럼, 인덱스, COMMENT). T002 결과에 따라 CHECK 제약 변경 SQL 포함.
- [ ] T009 구현: PaymentRepository에 findByIdForUpdate, findByOrdersForUpdate(PESSIMISTIC_WRITE, 락 대기 3초), 정리 대상 조회(APPROVING + 기준 시각 이전, 최대 50건), NEEDS_REVIEW 목록 조회 추가.


## 단계 2. 토스 클라이언트

- [ ] T010 테스트: TossPaymentClientTest (MockRestServiceServer). confirm 요청에 Idempotency-Key "whale-confirm-{orderId}" 헤더 포함, getPayment가 GET /v1/payments/{paymentKey} 호출 후 응답 매핑. 테스트를 위해 RestClient.Builder를 주입받도록 생성자 구조를 바꿀 수 있다.
- [ ] T011 구현: TossPaymentClient 타임아웃(연결 5초, 응답 30초, application.yaml toss.connect-timeout / toss.read-timeout), Idempotency-Key 헤더, getPayment + TossPaymentResponse record. T010 통과.


## 단계 3. 승인 흐름 (트랜잭션 분리)

- [ ] T012 테스트: PaymentConfirmFacadeTest (Mockito). 토스 성공 / 4xx / 5xx / 타임아웃 / completeApproval 실패 각각에서 호출되는 TxService 메서드, 응답 종류(성공·실패 예외·확인 중), Redis saveResult/delete 호출 여부.
- [ ] T013 [P] 테스트: SC-001 ConfirmConcurrencyTest (통합). markProcessing 항상 true 스파이, 같은 주문 confirm 20회 동시 → 토스 confirm 1회, 결제 SUCCESS.
- [ ] T014 [P] 테스트: SC-003 ConfirmConnectionTest (통합). Hikari 최대 풀 2, 토스 5초 지연 confirm 3건 진행 중에 다른 주문 confirm이 1초 안에 완료.
- [ ] T015 구현: PaymentApprovalTxService (startApproval, completeApproval, rejectApproval, markNeedsReview). 모든 메서드는 결제 행 락을 먼저 잡고 엔티티 메서드로 전이. completeApproval 안에서 Outbox enqueue + OrderCreatedEvent 발행.
- [ ] T016 구현: PaymentConfirmFacade. T012, T013, T014 통과.
- [ ] T017 구현: PaymentController가 Facade 호출, 확인 중이면 202 응답. PaymentService.confirm 제거, 공용 헬퍼 이동. 기존 테스트가 PaymentService.confirm을 호출하면 호출 대상만 Facade로 변경(기대값 유지).


## 단계 4. 결제 대기 정리와의 충돌 해소

- [ ] T018 테스트: SC-007 SweepSkipTest (통합). NEEDS_REVIEW, APPROVING 결제의 주문을 31분 전 생성으로 조정 → PaymentSweepScheduler 실행 → 주문 AWAITING_PAYMENT 유지. cancelPending API도 같은 조건에서 취소하지 않음.
- [ ] T019 구현: doCancelAwaitingPayment가 결제 행을 락으로 읽고 PENDING이 아니면 건너뜀. 락 순서(결제 → 주문) 준수. T018 통과.


## 단계 5. 정리 작업

- [ ] T020 테스트: PaymentApprovalReconcilerTest (Mockito). 토스 상태별 분기(T001 결과 반영), 30분 경계(29분 59초 / 30분 1초), 조회 실패 → NEEDS_REVIEW, 한 건 예외가 다음 건을 막지 않음.
- [ ] T021 [P] 테스트: SC-002 ApprovalRecoveryTest (통합). completeApproval 강제 실패 → APPROVING 잔류 → 기준 시각 6분 전으로 조정 → 정리 작업(토스 DONE) → 결제 SUCCESS, 주문 PENDING, Outbox 1건.
- [ ] T022 [P] 테스트: SC-008 LateApprovalTest (통합). 승인 시작 31분 전 + 토스 DONE → 주문 CANCELLED, 토스 cancel 1회, 결제 CANCELLED. 토스 cancel 실패 시 NEEDS_REVIEW(환불 실패). 환불 실패 건 재조회 시 환불 재시도.
- [ ] T023 구현: PaymentApprovalReconciler (@Scheduled 60초, reconcile(paymentId), 늦은 확정 처리) + 메트릭(payment.approval.unknown, payment.approval.reconciled, payment.needs_review.count). T020, T021, T022 통과.


## 단계 6. 관리자 화면

- [ ] T024 테스트: SC-006 PaymentReviewTest (통합). 토스 조회 실패 → NEEDS_REVIEW → GET /api/admin/payment-reviews 목록 노출 → 토스 DONE으로 변경 후 POST recheck → SUCCESS. ADMIN 외 권한은 403. 재조회 이력(reason에 실행자) 기록.
- [ ] T025 구현: AdminPaymentReviewController + PaymentReviewResponse record. T024 통과.
- [ ] T026 [P] 프론트: pages/admin/PaymentReviewPage.jsx(+css), api/adminPayment.js, App.jsx 라우트(/admin/payment-reviews), AdminLayout 메뉴. StockRestoreFailurePage 구조를 따른다.


## 단계 7. 고객 화면

- [ ] T027 [P] 구현: PaymentInfoResponse 상태 라벨 (APPROVING, NEEDS_REVIEW → "결제 확인 중").
- [ ] T028 [P] 프론트: PaymentSuccessPage. confirm 호출 timeout 10초, 202 또는 timeout이면 /orders/{orderId}로 이동.
- [ ] T029 프론트: OrderDetailPage. 결제 확인 중 배너, 3초 간격 재조회, 상태 변경 시 중단, 10분 후 중단 및 안내.
- [ ] T030 [사람] SC-005 수동 확인. dev 전용 설정으로 토스 응답 30초 지연 → 화면이 10초 안에 주문 상세로 이동하고, 서버 저장 후 결과가 반영되는지.


## 단계 8. 마무리

- [ ] T031 SC-004 전체 테스트 실행 (gradlew test). 실패 테스트는 구현을 고친다 (VII-4).
- [ ] T032 [P] Grafana 대시보드에 payment.needs_review.count, payment.approval.unknown 패널 추가.
- [ ] T033 [P] 문서: wiki domains/payment.md, architecture/saga-compensation.md 갱신, Home에 constitution과 specs 링크, conversations 기록.
- [ ] T034 [사람] dev DB에 T008 SQL 적용 → 동작 확인 → prod DB 적용 → 배포.
