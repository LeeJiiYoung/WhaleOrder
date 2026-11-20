# WhaleOrder Constitution (초안)

버전: 0.3.0 (초안)
작성일: 2026-11-20
상태: 검토 중. [NEEDS CLARIFICATION] 항목을 결정한 뒤 1.0.0으로 확정한다.
위치: spec-kit 초기화 후 .specify/memory/constitution.md 로 옮긴다.

이 문서는 새로 정한 규칙이 아니라, 현재 코드가 이미 지키고 있는 원칙을 옮겨 적은 것이다.
각 원칙의 "근거"는 그 원칙을 확인한 실제 코드 위치다.
모든 기능의 Plan 단계는 아래 원칙을 위반하지 않는지 게이트로 확인한다.


## 핵심 원칙

### I. 재고 변경은 반드시 StockLockFacade를 거친다

- 재고 차감·복구는 StockLockFacade.deductStock / restoreStock 으로만 한다. StockService를 직접 호출하지 않는다.
- 락 키는 stock:lock:{storeId}:{menuId} 로 매장·메뉴 단위로 잡는다.
- tryLock은 leaseTime을 지정하지 않는다 (watchdog 자동 갱신). 대기 시간은 5초.
- Stock 엔티티의 @Version 낙관적 락을 제거하지 않는다. 분산 락이 새는 경우의 마지막 방어선이다.
- 락 해제는 finally에서 isHeldByCurrentThread() 확인 후 한다.

이유: 분산 락과 DB 충돌 감지는 서로 다른 장애 상황을 막는다. 둘 중 하나만으로는 오버셀을 막을 수 없다.
근거: StockLockFacade, Stock(@Version), StockConcurrencyTest, StockLockTransactionBoundaryTest

결정(2026-11-20): 낙관적 락 충돌 재시도 값은 현재 값(최대 3회, 50ms 간격)을 유지한다. 실험으로 근거를 확보하기 전까지는 잠정값으로 보고, 값을 바꾸면 이 항목을 갱신한다.


### II. 결제 요청은 멱등하게 처리한다

- 결제 준비(prepare)와 승인(confirm)은 IdempotencyService 패턴을 따른다.
  getResult로 완료 결과 확인 → markProcessing(SET NX EX)으로 처리권 획득 → 성공 시 saveResult, 실패 시 delete.
- 같은 요청이 동시에 와도 외부 PG 호출은 한 번만 일어나야 한다.

이유: 네트워크 재시도나 더블 클릭으로 결제가 두 번 승인되는 것을 막는다.
근거: IdempotencyService, PaymentService, IdempotencyServiceTest

- Redis 멱등 키는 1차 방어다. 최종 방어는 DB 상태 전이(조건부 UPDATE)로 한다. 같은 결제가 PENDING에서 다음 상태로 넘어가는 것은 한 요청만 성공해야 한다.
- Redis 키가 사라져도(재시작, 장애 조치, TTL 만료) 외부 PG 호출이 두 번 일어나면 안 된다.

결정(2026-11-20): 멱등 키는 Redis에 그대로 두고, DB 상태 전이를 최종 방어선으로 추가한다. 결제 준비(prepare)는 중복 생성돼도 미결제 주문으로 남아 PaymentSweepScheduler가 정리하므로 대상에서 제외한다.


### III. 이벤트 발행은 Outbox를 통한다

- 비즈니스 트랜잭션에서 Kafka로 직접 발행하지 않는다. KafkaOutboxService.enqueue 로 예약하고 워커가 발행한다.
- enqueue는 반드시 호출자 트랜잭션 안에서 호출한다. propagation을 REQUIRES_NEW로 바꾸지 않는다.
- enqueue 예외를 catch하지 않는다. 예외가 호출자 트랜잭션을 롤백시켜야 원자성이 지켜진다.

이유: DB 커밋과 메시지 발행 사이에 한쪽만 성공하는 상황을 없앤다.
근거: KafkaOutboxService, KafkaOutboxWorker, global/outbox 테스트 6종


### IV. 실패는 분류하고, 사라지게 두지 않는다

- 재시도해도 결과가 같은 실패(IllegalArgumentException, IllegalStateException 예: 재고 부족)는 재시도 없이 DLT로 보낸다.
- 일시적 실패(예: 락 획득 실패)는 재시도한다. 현재 Kafka 재시도는 1초 간격 3회.
- Consumer는 처리 시작 시 상태를 확인해 같은 메시지를 두 번 처리하지 않는다 (stockDeducted, CANCELLED 확인).
- 보상(재고 복구·주문 취소·결제 환불)은 OrderProcessingService가 순서를 조정한다 (Orchestration).
- 보상이 끝내 실패하면 반드시 저장소에 기록하고 관리자에게 알린다. 로그만 남기고 끝내지 않는다. 세부 규칙은 IV-1을 따른다.

이유: 실패한 주문이 어디에도 남지 않으면 돈과 재고가 조용히 어긋난다.
근거: KafkaConfig(DefaultErrorHandler), OrderProcessingService.process / compensate, StockRestoreFailure, SagaCompensationTest

[NEEDS CLARIFICATION] 현재 DLT Consumer의 compensate()가 실패하면 log.error만 남는다. 위 마지막 규칙을 이미 위반하는 지점이다. 별도 기능(보상 실패 자동 재처리)으로 해결한다. 첫 SDD 기능은 001 결제 승인 트랜잭션 분리로 변경됐다.


### IV-1. 보상 실패 처리 세부 규칙

#### 보상 실패의 정의

다음 중 하나라도 해당하면 "보상 실패"로 본다.
- 보상 단계(재고 복구, 주문 취소, 결제 환불) 중 하나가 정해진 재시도를 모두 소진하고도 실패했다.
- DLT Consumer에서 compensate() 호출 자체가 예외로 끝났다.
- 보상 결과를 확인할 수 없다. 예: 결제 취소 API가 타임아웃되어 환불 여부를 모른다.

마지막 경우는 "실패"로 확정할 수 없지만, 사람이 확인하기 전까지는 실패와 같은 방식으로 다룬다.

#### 규칙 1. 기록이 먼저다

- 보상 실패는 PostgreSQL 테이블에 기록한다. 로그, Redis, 애플리케이션 메모리는 기록으로 인정하지 않는다. 재시작이나 TTL 만료로 사라지기 때문이다.
- 기록은 보상 트랜잭션과 독립적으로 커밋되어야 한다. 보상 트랜잭션이 롤백돼도 실패 기록은 남아야 한다. 트랜잭션 밖에서 저장하거나 별도 트랜잭션(REQUIRES_NEW)으로 저장한다.
  원칙 III의 REQUIRES_NEW 금지는 Outbox enqueue에만 해당한다. 실패 기록은 원본 트랜잭션과 함께 사라지면 안 되므로 오히려 분리해야 한다.
- 기록에는 최소한 다음 정보를 담는다.
  - 주문 ID
  - 실패 단계: STOCK_RESTORE, ORDER_CANCEL, PAYMENT_REFUND 중 하나
  - 대상 정보: 재고면 storeId, menuId, quantity / 결제면 결제 ID와 금액
  - 실패 원인: 예외 클래스명과 메시지
  - 시도 횟수
  - 최초 실패 시각, 마지막 실패 시각
  - 처리 상태: PENDING(미처리), RESOLVED(해결)
  - 해결 정보: 처리자, 처리 시각, 처리 메모
- 같은 주문의 같은 단계에서 미처리(PENDING) 기록은 1건만 둔다. 다시 실패하면 새 행을 만들지 않고 시도 횟수와 마지막 실패 시각을 갱신한다.
- 해결된 기록은 삭제하지 않는다. 상태만 RESOLVED로 바꿔 이력을 남긴다.

#### 규칙 2. 알림은 기록 다음이다

- 기록을 저장한 뒤에 알린다. 알림이 실패해도 기록은 남아 있어야 한다.
- 알림을 보내다 발생한 예외는 로그로 남기고 보상 흐름으로 다시 던지지 않는다. 알림 실패 때문에 기록이나 다른 보상 단계가 롤백되면 안 된다.
- 실시간 알림(SSE)만으로는 충분하지 않다. 알림 시점에 접속한 관리자가 없으면 그대로 사라진다. 관리자가 나중에 접속해도 미처리 건을 확인할 수 있는 조회 경로가 반드시 있어야 한다.
- 알림에는 주문 ID, 실패 단계, 대상(수량 또는 금액), 필요한 조치를 포함한다.

#### 규칙 3. 해결 경로가 있어야 한다

- 관리자는 미처리 목록을 조회하고, 건별로 "재처리" 또는 "수동 해결 완료"를 할 수 있어야 한다.
- 해결할 때는 처리자, 처리 시각, 메모를 남긴다.
- 재처리는 멱등해야 한다. 이미 복구된 재고를 다시 복구하거나, 이미 환불된 결제를 다시 환불하면 안 된다. 재처리 전에 대상의 현재 상태를 먼저 확인한다.

#### 규칙 4. 관측할 수 있어야 한다

- 실패 단계별 실패 횟수를 메트릭(Counter)으로 남긴다.
- 미처리 건수를 메트릭(Gauge)으로 노출해 Grafana에서 볼 수 있게 한다.
- log.error는 계속 남긴다. 다만 log.error가 유일한 흔적이어서는 안 된다.

#### 금지 사항

- 보상 실패를 catch한 뒤 로그만 남기고 끝내는 것
- 기록 없이 return하거나 예외를 삼키는 것
- 실패 기록을 보상 트랜잭션 안에서만 저장해 롤백 때 함께 사라지게 하는 것

#### 현재 코드와의 차이

- 재고 복구(STOCK_RESTORE): StockRestoreFailure 저장과 SSE 알림이 있어 규칙 1, 2를 부분적으로 지킨다. 하지만 처리 상태, 시도 횟수, 해결 정보 필드가 없고, 관리자 API가 조회(GET)만 있어 규칙 3을 지키지 못한다. 저장이 보상 트랜잭션과 분리되어 있는지도 확인이 필요하다.
- 주문 취소(ORDER_CANCEL), 결제 환불(PAYMENT_REFUND): DLT Consumer에서 log.error만 남긴다. 규칙 1~4를 모두 지키지 못한다.

[NEEDS CLARIFICATION] 기존 StockRestoreFailure를 확장해 모든 단계를 담을지, 새 테이블(예: CompensationFailure)을 만들고 기존 테이블을 옮길지 결정한다.
[NEEDS CLARIFICATION] SSE 외에 외부 알림 채널(이메일, 슬랙, 텔레그램 등)이 필요한지 결정한다.
[NEEDS CLARIFICATION] 자동 재처리를 할지, 한다면 몇 번까지 어떤 간격으로 할지 결정한다. 이것은 Constitution이 아니라 첫 기능 스펙에서 정한다. Constitution은 기록, 알림, 해결 경로가 있어야 한다는 것까지만 강제한다.


### V. Kafka가 꺼져 있어도 주문은 처리된다

- Kafka 관련 빈은 kafka.enabled 설정으로 켜고 끈다.
- Kafka에 의존하는 새 기능은 kafka.enabled=false 일 때의 동작을 Plan에 함께 적는다.

이유: 운영 환경(t3.micro)은 Kafka를 띄울 수 없어서 우회 경로로 동작한다.
근거: OrderKafkaConsumer, OrderKafkaProducer, KafkaConfig 의 @ConditionalOnProperty


### VI. 계층과 모델 규칙

- Controller → Service → Repository. Controller에는 요청 검증과 응답 변환만 둔다.
- 엔티티에 @Setter, @Data를 쓰지 않는다. 기본 생성자는 protected, 생성은 @Builder 생성자로 한다.
- 엔티티 상태는 의미 있는 도메인 메서드로만 바꾼다 (예: Stock.deduct, Stock.restore).
- 요청·응답 DTO는 record를 쓴다. 예외: multipart 업로드를 @ModelAttribute로 받는 요청 DTO는 @Getter/@Setter 클래스를 허용한다 (GoodsCreateRequest, MenuCreateRequest, MenuUpdateRequest).
- 모든 API 응답은 ApiResponse로 감싼다. 예외 응답은 GlobalExceptionHandler에서만 만든다.
- 고객 API는 /api/..., 관리자 API는 /api/admin/... 로 시작한다.
- 동적 쿼리는 QueryDSL(RepositoryCustom + Impl)로 작성한다.

근거: domain/*/entity (Setter 사용 0건), domain/*/dto (42개 중 38개 record), ApiResponse, GlobalExceptionHandler, OrderRepositoryCustomImpl


### VII. 테스트로 증명한 뒤 머지한다

#### VII-1. 테스트 먼저 쓴다 (TDD)
- 새 비즈니스 로직은 실패하는 테스트를 먼저 작성하고, 그 테스트를 통과시키는 구현을 한다.
- 예외: 설정 파일 변경, 단순 화면 변경, 로직 없는 DTO 추가

이유: 구현을 먼저 하면 AI가 자기 코드에 맞춰 테스트를 쓰게 되고, 버그가 있어도 테스트가 그 버그를 정답으로 굳힐 수 있다. 테스트가 먼저 있으면 구현의 목표가 명확해진다.

#### VII-2. 테스트 종류는 검증 대상에 맞춘다
- 단위 테스트 (스프링 없음): 엔티티 메서드, 상태 전이 규칙, 계산과 분기
- Mockito 테스트 (@ExtendWith(MockitoExtension.class)): 외부 의존(PG 클라이언트, 다른 서비스)이 있는 서비스 흐름과 예외 분기
- 통합 테스트 (@SpringBootTest + TestContainerBase, Kafka는 @EmbeddedKafka): 실제 DB나 인프라가 있어야 의미 있는 것만. 트랜잭션 경계, 락, 동시성, 쿼리, Kafka 연동
- 통합 테스트로만 검증할 수 있는 것이 아니면 더 가벼운 종류를 쓴다.

참고 모델: global/outbox 테스트 — KafkaOutboxTest(단위), KafkaOutboxWorkerTest·KafkaOutboxRowProcessorTest(Mockito), KafkaOutboxRepositoryTest·KafkaOutboxServiceTest(통합)

#### VII-3. 적용 범위
- 이 기준은 새로 작성하거나 수정하는 테스트부터 적용한다. 기존 테스트를 옮기기 위한 리팩터링은 하지 않는다.

#### VII-4. 테스트의 기대값을 구현에 맞춰 바꾸지 않는다
- 테스트를 통과시키려고 테스트의 기대값이나 검증 조건을 바꾸지 않는다. 구현을 고친다.
- 기대값 변경은 스펙이 바뀌었다는 근거가 있을 때만, 사람 승인 후에 한다. 변경 이유는 커밋 메시지나 conversations 로그에 남긴다.
- 테스트를 @Disabled 처리하거나 삭제해서 통과시키는 것도 같은 규칙을 따른다.

#### VII-5. 동시성·정합성 변경은 최종 상태로 검증한다
- 재고, 결제, 멱등성, 보상 흐름을 바꾸면 동시 요청 테스트를 함께 작성한다.
- 검증 기준은 HTTP 응답이 아니라 최종 상태(DB 잔여 재고, 결제 상태)다.

이유: 비동기 구조에서는 200 응답이 성공을 뜻하지 않는다. 실제로 k6 테스트가 재고 -90을 정상으로 오판한 적이 있다.
근거: StockConcurrencyTest, TestContainerBase, k6 teardown 잔여 재고 검증

결정(2026-11-20): TDD 필수, 테스트 종류 3단 기준 적용, 기존 테스트는 유지하고 새 테스트부터 적용, 기대값 임의 변경 금지


### VIII. 문서와 코드는 함께 움직인다

- 작업 전 docs/wiki/Home.md 에서 관련 문서를 먼저 읽는다.
- 문서와 코드가 다르면 코드를 사실로 보고, 문서를 갱신 대상으로 표시한다.
- 주요 결정은 docs/wiki/conversations/YYYY-MM-DD.md 에 남긴다.
- 기능 스펙(spec, plan, tasks)이 끝나면 관련 wiki 문서에 링크한다.


### X. 외부 API 호출은 DB 트랜잭션 밖에서 한다

- PG(토스) 등 외부 API를 @Transactional 메서드 안에서 호출하지 않는다.
- 외부 호출 전에 짧은 트랜잭션으로 "진행 중" 상태를 커밋하고, 호출 결과는 새 트랜잭션으로 저장한다.
- "진행 중" 상태에 멈춘 건은 외부 조회 API로 실제 결과를 확인해 정리하는 경로가 있어야 한다.

이유: 외부 호출 중에 DB 락과 커넥션을 잡지 않기 위해서다. 그리고 외부에서는 성공했는데 DB 커밋이 실패하는 경우(돈은 나갔는데 기록이 없음)를 감지하고 복구할 수 있게 하기 위해서다.
근거: 현재 위반 지점 2곳 — PaymentService.confirm()의 토스 승인 호출, PaymentService.cancelPayment()의 토스 취소 호출. 첫 적용 스펙: specs/001-payment-external-call-tx-split

임시 예외(2026-11-20): 001 기능의 늦게 확정된 결제 자동 환불(FR-016)은 기존 cancelPayment()를 재사용해 트랜잭션 안에서 토스 취소를 호출한다. 환불 트랜잭션 분리 기능에서 해소한다.

### IX. 결정과 되돌리기 어려운 작업은 사람이 한다

- AI는 탐색, 초안, 구현, 리뷰를 맡는다. 설계 결정과 머지 승인은 사람이 한다.
- 커밋, 푸시, 스키마 변경은 사람 확인 후 진행한다.


## 기술 제약

- Java 21, Spring Boot 3.x, PostgreSQL, Redis(Redisson), Kafka, QueryDSL
- 새 외부 의존성을 추가하면 Plan에 이유와 대안을 적는다.
- 코드 주석은 한국어, 변수·함수명은 영어 camelCase.


## 개정 규칙

- Constitution 변경은 사람이 승인한다.
- 원칙 추가·삭제는 MINOR, 문구 수정은 PATCH, 기존 원칙을 뒤집는 변경은 MAJOR 버전을 올린다.
- 변경 이유는 conversations 로그에 남긴다.


## 초안 작성 중 발견한 불일치

1. [해결 2026-11-20] CLAUDE.md의 동시성 제어 설명을 실제 코드(Redisson 분산 락 + @Version 낙관적 락)로 수정했다.
2. [해결 2026-11-20] CLAUDE.md의 실시간 주문 현황 저장소 설명을 실제 코드(OrderSseService 메모리, 단일 서버)로 수정했다.
3. [미해결 — 001 구현 첫 작업으로 처리 예정] StockLockFacade는 InterruptedException을 IllegalStateException으로 감싼다. 원칙 IV 분류상 이 예외는 재시도 없이 바로 DLT로 간다. 배포 등으로 컨슈머 스레드가 인터럽트되면 정상 주문이 DLT → 보상 → 취소·환불될 수 있다. 수정 방향: deductStock, restoreStock에서 재시도 대상인 StockLockException을 던진다(인터럽트 플래그 유지). Mockito 테스트 먼저 작성(VII-1).
4. [해결 2026-11-20] 요청 DTO 3개가 @Getter/@Setter 클래스인 이유는 multipart 업로드용 @ModelAttribute 바인딩이다. 원칙 VI에 예외로 명시했다.
