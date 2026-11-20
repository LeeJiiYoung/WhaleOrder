# 결제 멱등성 층 2 (DB UNIQUE) + 층 7 (TTL 조정) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 클라이언트 발급 `requestId` 를 키로 하는 DB UNIQUE 제약을 추가해, Redis 멱등성(층 1)이 뚫린 상황에서도 중복 결제가 커밋되지 않게 만든다.

**Architecture:** 두 층이 **서로 다른 키**를 쓴다. 층 1은 지금처럼 카트 해시 + 짧은 TTL로 더블클릭·네트워크 재시도를 즉시 거르고, 층 2는 시간과 무관한 `requestId` 로 `payment_idempotency` 테이블에 UNIQUE row를 남겨 최종 차단한다. row는 **결제 성공 시에만** 기록하므로 실패 후 재시도는 같은 `requestId` 로 그대로 가능하다.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Data JPA, PostgreSQL 16, Redisson, Micrometer, React (Vite), k6

## Global Constraints

- 코드 주석은 **한국어**, 변수/함수명은 영어 camelCase (프로젝트 CLAUDE.md 규칙).
- **커밋은 `feature/payment-idempotency-layer2` 브랜치 안에서만 한다.** 각 태스크 끝에서 커밋한다. `main` 병합과 `git push` 는 사용자가 직접 하므로 **절대 실행하지 말 것.**
- `ddl-auto` 는 dev/prod `update`, 운영 기본 `validate`, test `create-drop`. 엔티티가 스키마의 원본이고 `src/main/resources/db/schema.sql` 은 **문서용**이라 항상 함께 갱신한다.
- 통합 테스트는 `@SpringBootTest` + `@ActiveProfiles("test")` + `TestContainerBase` 상속 + `@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})`. `@EmbeddedKafka` 가 없으면 KafkaConfig 때문에 컨텍스트 자체가 뜨지 않는다.
- `TestContainerBase` 가 매 테스트 전 public 스키마 전체를 `TRUNCATE ... RESTART IDENTITY CASCADE` 한다.
- 테스트 실행: `./gradlew test --tests "<패턴>"`. Windows에서 EmbeddedKafka 종료 시 `TestUtils : Error deleting ... FileSystemException` 이 콘솔에 찍히는데 **알려진 노이즈**다. 성공/실패 판정은 `build/reports/tests/test/index.html` 로 한다.
- `requestId` 형식은 36자 UUID 문자열. 정규식 `^[0-9a-fA-F-]{36}$` 로 통일한다 (DTO 검증, DB 컬럼 길이, 프런트·k6 생성기 모두 동일 기준).

---

### Task 1: payment_idempotency 테이블과 엔티티

**Files:**
- Create: `src/main/java/com/whale/order/global/idempotency/PaymentIdempotency.java`
- Create: `src/main/java/com/whale/order/global/idempotency/PaymentIdempotencyRepository.java`
- Modify: `src/main/resources/db/schema.sql` (파일 끝에 추가)
- Test: `src/test/java/com/whale/order/global/idempotency/PaymentIdempotencyRepositoryTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `PaymentIdempotency.of(Long memberId, String requestId, Long paymentId)` → `PaymentIdempotency`
  - `PaymentIdempotencyRepository extends JpaRepository<PaymentIdempotency, Long>`
  - `PaymentIdempotencyRepository.deleteByCreatedAtBefore(LocalDateTime cutoff)` → `long`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/whale/order/global/idempotency/PaymentIdempotencyRepositoryTest.java`

```java
package com.whale.order.global.idempotency;

import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결제 멱등성 층 2 — UNIQUE 제약 검증.
 *
 * <p>Testcontainers 로 실제 PostgreSQL 을 쓰므로 제약이 그대로 강제된다.
 * 클래스에 {@code @Transactional} 을 붙이지 않는다 — 제약 위반이 트랜잭션을
 * rollback-only 로 만들어 후속 검증을 방해하기 때문. 격리는
 * {@code TestContainerBase} 의 TRUNCATE 가 담당한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class PaymentIdempotencyRepositoryTest extends TestContainerBase {

    @Autowired PaymentIdempotencyRepository repository;

    @Test
    @DisplayName("같은 회원이 같은 requestId 로 두 번 기록하면 UNIQUE 제약에 걸린다")
    void 동일_회원_동일_requestId_중복_저장_차단() {
        String requestId = "11111111-1111-4111-8111-111111111111";
        repository.saveAndFlush(PaymentIdempotency.of(1L, requestId, 100L));

        assertThatThrownBy(() ->
                repository.saveAndFlush(PaymentIdempotency.of(1L, requestId, 200L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("회원이 다르면 같은 requestId 라도 각각 기록된다")
    void 다른_회원_동일_requestId_는_허용() {
        String requestId = "22222222-2222-4222-8222-222222222222";
        repository.saveAndFlush(PaymentIdempotency.of(1L, requestId, 100L));
        repository.saveAndFlush(PaymentIdempotency.of(2L, requestId, 200L));

        assertThat(repository.count()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```
./gradlew test --tests "com.whale.order.global.idempotency.PaymentIdempotencyRepositoryTest"
```

Expected: 컴파일 실패 — `PaymentIdempotency`, `PaymentIdempotencyRepository` 심볼을 찾을 수 없음.

- [ ] **Step 3: 엔티티 작성**

`src/main/java/com/whale/order/global/idempotency/PaymentIdempotency.java`

```java
package com.whale.order.global.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 결제 멱등성 층 2 — DB UNIQUE 기록.
// 층 1(Redis, 카트 해시 + TTL)이 뚫린 상황에서 동일 결제 요청이 두 번 커밋되는 것을 막는 최후 방어선.
// 키는 클라이언트가 결제 시도당 1개 발급하는 requestId 이며, 시간과 무관하므로 영구 UNIQUE 가 성립한다.
// memberId 를 복합 키에 포함하는 이유: requestId 단독이면 타인이 같은 UUID 를 보내 남의 결제를 막을 수 있다.
@Entity
@Table(
        name = "payment_idempotency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_idempotency",
                columnNames = {"member_id", "request_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private PaymentIdempotency(Long memberId, String requestId, Long paymentId) {
        this.memberId = memberId;
        this.requestId = requestId;
        this.paymentId = paymentId;
        this.createdAt = LocalDateTime.now();
    }

    // 결제 성공 시에만 기록한다. 실패 결제는 row 를 남기지 않아 같은 requestId 로 재시도할 수 있다.
    public static PaymentIdempotency of(Long memberId, String requestId, Long paymentId) {
        return new PaymentIdempotency(memberId, requestId, paymentId);
    }
}
```

- [ ] **Step 4: 리포지토리 작성**

`src/main/java/com/whale/order/global/idempotency/PaymentIdempotencyRepository.java`

```java
package com.whale.order.global.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface PaymentIdempotencyRepository extends JpaRepository<PaymentIdempotency, Long> {

    // 청소 배치용. 삭제된 건수 반환. clearAutomatically 로 L1 캐시 정리.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PaymentIdempotency p WHERE p.createdAt < :cutoff")
    long deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
```

- [ ] **Step 5: schema.sql 갱신**

`src/main/resources/db/schema.sql` **파일 맨 끝**(현재 마지막 줄은 `COMMENT ON COLUMN kafka_outbox.published_at ...`)에 아래를 붙인다.

```sql

-- ====================================
-- 결제 멱등성 (층 2 — DB UNIQUE 최종 방어선)
-- ====================================
CREATE TABLE IF NOT EXISTS payment_idempotency (
    id          BIGSERIAL   PRIMARY KEY,
    member_id   BIGINT      NOT NULL,
    request_id  VARCHAR(36) NOT NULL,
    payment_id  BIGINT      NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_payment_idempotency UNIQUE (member_id, request_id)
);

CREATE INDEX IF NOT EXISTS idx_payment_idempotency_created_at
    ON payment_idempotency (created_at);

COMMENT ON TABLE  payment_idempotency            IS '결제 멱등성 층 2. 클라이언트 발급 requestId 로 중복 결제를 DB 레벨에서 최종 차단한다. 결제 성공 시에만 기록.';
COMMENT ON COLUMN payment_idempotency.id         IS '멱등성 기록 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN payment_idempotency.member_id  IS '결제 회원 ID. requestId 단독 UNIQUE 면 타인이 같은 UUID 로 결제를 막을 수 있어 복합 키로 사용';
COMMENT ON COLUMN payment_idempotency.request_id IS '클라이언트가 결제 시도당 1회 발급하는 UUID (36자). 재시도 시 동일 값 재사용';
COMMENT ON COLUMN payment_idempotency.payment_id IS '이 요청으로 생성된 결제 ID. 감사·디버깅용 (FK 제약 없음)';
COMMENT ON COLUMN payment_idempotency.created_at IS '기록 생성 시각. 정리 배치의 기준';
```

FK를 걸지 않는 이유는 `kafka_outbox` 와 같다 — 회원 탈퇴 익명화 정책, 정리 배치 순서와 얽히지 않게 하려는 것이다.

- [ ] **Step 6: 테스트가 통과하는지 확인**

```
./gradlew test --tests "com.whale.order.global.idempotency.PaymentIdempotencyRepositoryTest"
```

Expected: 2개 테스트 PASS.

- [ ] **Step 7: 커밋**

변경한 파일만 스테이징해 커밋한다 (`git add -A` 금지 — 다른 태스크의 산출물이 섞인다). `git push` 는 하지 않는다.

```
feat(idempotency): payment_idempotency 테이블·엔티티 추가 (멱등성 층 2 기반)
```

---

### Task 2: pay() 에 층 2 통합

**Files:**
- Modify: `src/main/java/com/whale/order/domain/payment/dto/PaymentRequest.java`
- Modify: `src/main/java/com/whale/order/domain/payment/service/PaymentService.java`
- Modify: `src/main/resources/application.yaml` (파일 끝)
- Modify: `src/test/resources/application-test.yaml` (파일 끝)
- Test: `src/test/java/com/whale/order/domain/payment/service/PaymentServiceIdempotencyTest.java`

**Interfaces:**
- Consumes: `PaymentIdempotency.of(Long, String, Long)`, `PaymentIdempotencyRepository` (Task 1)
- Produces:
  - `PaymentRequest` 에 `String requestId` 컴포넌트 추가 — 생성자 시그니처가 `(PaymentMethod, Long, OrderType, Long, String, String)` 로 바뀐다 (마지막이 `requestId`)
  - 메트릭 `payment.idempotency.conflict{layer=redis|db}`
  - 설정 키 `payment.mock.success-rate` (기본 90)

**Mock 성공률 설정값 분리가 이 태스크에 포함된 이유:** 현재 `ThreadLocalRandom ... < 90` 이 하드코딩돼 있어 결제 성공 경로가 10% 확률로 실패한다. 이 태스크의 테스트가 성공 경로를 요구하므로 선행 조건이다.

- [ ] **Step 1: 설정값 추가**

`src/main/resources/application.yaml` 맨 끝에 추가:

```yaml

# Mock PG 결제
payment:
  mock:
    success-rate: 90   # % — 테스트 프로파일에서 100 으로 고정해 성공 경로를 결정적으로 검증한다
```

`src/test/resources/application-test.yaml` 맨 끝에 추가:

```yaml

# Mock PG 를 항상 성공시켜 결제 성공 경로를 결정적으로 테스트한다
payment:
  mock:
    success-rate: 100
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/whale/order/domain/payment/service/PaymentServiceIdempotencyTest.java`

```java
package com.whale.order.domain.payment.service;

import com.whale.order.domain.cart.dto.CartAddRequest;
import com.whale.order.domain.cart.service.CartService;
import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.order.entity.OrderType;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.payment.dto.PaymentRequest;
import com.whale.order.domain.payment.entity.PaymentMethod;
import com.whale.order.domain.stock.entity.Stock;
import com.whale.order.domain.stock.repository.StockRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.global.exception.DuplicateRequestException;
import com.whale.order.global.idempotency.IdempotencyService;
import com.whale.order.global.idempotency.PaymentIdempotencyRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 결제 멱등성 층 2 (DB UNIQUE) 통합 검증.
 *
 * <p>층 1(Redis)을 {@code @MockitoBean} 으로 무력화해 <b>Redis 페일오버 상황</b>을 시뮬레이션한다.
 * markProcessing 이 항상 true 를 반환하므로 동일 요청이 그대로 결제 처리까지 진입하고,
 * 그때 DB UNIQUE 가 막아내는지를 본다.
 *
 * <p>Spring Boot 3.5 이므로 {@code @MockitoBean} 을 쓴다 ({@code @MockBean} 은 3.4부터 deprecated).
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class PaymentServiceIdempotencyTest extends TestContainerBase {

    @Autowired PaymentService paymentService;
    @Autowired CartService cartService;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentIdempotencyRepository idempotencyRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired StockRepository stockRepository;

    // 층 1 무력화 — Redis 가 중복을 전혀 걸러주지 못하는 상태
    @MockitoBean IdempotencyService idempotencyService;

    private Member customer;
    private Store store;
    private Menu menu;

    @BeforeEach
    void setUp() {
        // markProcessing 만 스텁한다. getResult 는 목 기본값이 null 이라 그대로 두면
        // "캐시된 결과 없음"으로 동작하고, saveResult/delete 는 void 라 스텁이 필요 없다.
        when(idempotencyService.markProcessing(anyString())).thenReturn(true);

        Member owner = memberRepository.save(Member.builder()
                .name("점주").provider(AuthProvider.LOCAL).role(MemberRole.OWNER).build());
        customer = memberRepository.save(Member.builder()
                .name("고객").provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());

        store = storeRepository.save(Store.builder()
                .owner(owner).name("테스트 매장").postalCode("12345")
                .address("서울시 강남구 테스트로 1")
                .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(21, 0))
                .build());
        store.open();   // pay() 는 OPEN 매장만 접수한다
        storeRepository.save(store);

        menu = menuRepository.save(Menu.builder()
                .name("아메리카노").basePrice(4500L).category(MenuCategory.BEVERAGE).build());

        stockRepository.save(Stock.builder()
                .store(store).menu(menu).quantity(100).build());
    }

    @Test
    @DisplayName("Redis 가 뚫려도 같은 requestId 두 번째 결제는 DB UNIQUE 가 막는다")
    void 동일_requestId_재요청은_층2에서_차단된다() {
        String requestId = "33333333-3333-4333-8333-333333333333";

        장바구니_담기();
        paymentService.pay(customer.getMemberId(), 결제요청(requestId));

        // 첫 결제 후 AFTER_COMMIT 리스너가 카트를 비우므로 다시 채운다.
        // (카트가 비어 있으면 pay() 가 층 2 도달 전에 IllegalStateException 으로 끝난다)
        장바구니_담기();

        assertThatThrownBy(() -> paymentService.pay(customer.getMemberId(), 결제요청(requestId)))
                .isInstanceOf(DuplicateRequestException.class);

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(idempotencyRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("requestId 가 다르면 같은 카트라도 각각 주문된다")
    void 다른_requestId_는_정상_주문된다() {
        장바구니_담기();
        paymentService.pay(customer.getMemberId(), 결제요청("44444444-4444-4444-8444-444444444444"));

        장바구니_담기();
        paymentService.pay(customer.getMemberId(), 결제요청("55555555-5555-4555-8555-555555555555"));

        assertThat(orderRepository.count()).isEqualTo(2);
        assertThat(idempotencyRepository.count()).isEqualTo(2);
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────

    private void 장바구니_담기() {
        cartService.addItem(customer.getMemberId(), new CartAddRequest(
                store.getStoreId(), menu.getMenuId(), 1, List.of()));
    }

    private PaymentRequest 결제요청(String requestId) {
        return new PaymentRequest(
                PaymentMethod.CREDIT_CARD,
                store.getStoreId(),
                OrderType.TAKEOUT,
                menu.getBasePrice(),
                null,
                requestId);
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

```
./gradlew test --tests "com.whale.order.domain.payment.service.PaymentServiceIdempotencyTest"
```

Expected: 컴파일 실패 — `PaymentRequest` 생성자 인자 개수 불일치 (현재 5개, 테스트는 6개).

- [ ] **Step 4: PaymentRequest 에 requestId 추가**

`src/main/java/com/whale/order/domain/payment/dto/PaymentRequest.java` 전체를 아래로 교체한다.

```java
package com.whale.order.domain.payment.dto;

import com.whale.order.domain.order.entity.OrderType;
import com.whale.order.domain.payment.entity.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record PaymentRequest(
        @NotNull(message = "결제 수단을 선택해주세요") PaymentMethod method,
        @NotNull(message = "매장을 선택해주세요") Long storeId,
        @NotNull(message = "주문 방식을 선택해주세요") OrderType orderType,
        // 클라이언트가 결제 화면에서 본 표시 금액. 서버는 cart 기준으로 재계산해 일치 여부 검증.
        // 실제 청구액은 항상 서버 cart 기준 — 이 값은 비교용일 뿐 PG 에 전달되지 않음.
        @NotNull(message = "결제 금액 정보가 누락되었습니다")
        @PositiveOrZero(message = "결제 금액은 0 이상이어야 합니다") Long expectedAmount,
        String customerRequest,
        // 멱등성 층 2 키. 클라이언트가 결제 시도당 1회 발급하는 UUID 로, 재시도 시 동일 값을 재사용한다.
        // 층 1(카트 해시)과 달리 시간에 의존하지 않으므로 DB UNIQUE 제약의 키가 될 수 있다.
        @NotBlank(message = "요청 식별자가 누락되었습니다")
        @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "요청 식별자 형식이 올바르지 않습니다")
        String requestId
) {}
```

- [ ] **Step 5: PaymentService 수정**

`src/main/java/com/whale/order/domain/payment/service/PaymentService.java` 를 네 군데 고친다.

**(5-1) import 추가** — 기존 import 블록에 아래를 넣는다.

```java
import com.whale.order.global.idempotency.PaymentIdempotency;
import com.whale.order.global.idempotency.PaymentIdempotencyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
```

**(5-2) 필드 추가** — `private final MeterRegistry meterRegistry;` 바로 아래에 넣는다.

```java
    private final PaymentIdempotencyRepository paymentIdempotencyRepository;

    // Mock PG 성공률(%). 테스트 프로파일에서 100 으로 고정해 결제 성공 경로를 결정적으로 검증한다.
    @Value("${payment.mock.success-rate:90}")
    private int mockSuccessRate;
```

`@RequiredArgsConstructor` 가 `final` 필드만 생성자로 받으므로 `mockSuccessRate` 는 필드 주입으로 남는다.

**(5-3) 층 1 충돌 계측 + 성공률 적용 + 층 2 기록**

`log.warn("[결제] 중복 요청 감지 ...")` 줄 **바로 위**에 계측을 추가한다.

```java
            incrementIdempotencyConflict("redis");
```

`boolean success = ThreadLocalRandom.current().nextInt(100) < 90;` 를 아래로 바꾼다.

```java
            boolean success = ThreadLocalRandom.current().nextInt(100) < mockSuccessRate;
```

성공 분기에서 `orderHistoryRepository.save(OrderStatusHistory.builder() ... .build());` 다음, `// Outbox 도입:` 주석 **바로 앞**에 층 2 기록을 넣는다.

```java
                // 멱등성 층 2 — 같은 requestId 로 이미 커밋된 결제가 있으면 여기서 UNIQUE 위반이 난다.
                // saveAndFlush 로 즉시 터뜨려야 outbox row 를 만들고 나서 롤백하는 낭비를 피한다.
                paymentIdempotencyRepository.saveAndFlush(PaymentIdempotency.of(
                        memberId, request.requestId(), payment.getPaymentId()));
```

**(5-4) 예외 변환 catch 절 추가**

기존 `} catch (Exception e) {` **바로 앞**에 새 catch 절을 넣는다.

```java
        } catch (DataIntegrityViolationException e) {
            // 층 2 차단. 제약 위반으로 트랜잭션은 이미 rollback-only 라 주문·결제·outbox 가 통째로 사라진다.
            // 타입을 바꾸는 이유: DataIntegrityViolationException 은 핸들러가 없어 500 이 나간다.
            idempotencyService.delete(key);
            log.warn("[결제] 층2 멱등성 충돌 memberId={} requestId={}", memberId, request.requestId());
            incrementIdempotencyConflict("db");
            throw new DuplicateRequestException("이미 처리된 결제 요청입니다. 주문 내역을 확인해주세요.");
```

**(5-5) 헬퍼 메서드 추가** — `generateIdempotencyKey(...)` 메서드 **바로 아래**에 넣는다.

```java
    // 멱등성 충돌 계측. layer=redis 는 층 1, layer=db 는 층 1이 뚫려 층 2가 막은 경우.
    // 두 값의 비율이 곧 "Redis 단일 방어로 충분했는가"에 대한 답이 된다.
    private void incrementIdempotencyConflict(String layer) {
        Counter.builder("payment.idempotency.conflict")
                .tag("layer", layer)
                .description("멱등성 중복 요청 차단 횟수")
                .register(meterRegistry).increment();
    }
```

- [ ] **Step 6: 테스트가 통과하는지 확인**

```
./gradlew test --tests "com.whale.order.domain.payment.service.PaymentServiceIdempotencyTest"
```

Expected: 2개 테스트 PASS.

- [ ] **Step 7: 전체 회귀 테스트**

```
./gradlew test
```

Expected: 전부 PASS.

`PaymentRequest` 를 직접 생성하는 코드는 이 계획이 새로 만드는 `PaymentServiceIdempotencyTest` 뿐이다 (프로덕션 코드에서는 Jackson 이 요청 바디로부터 만들고, 기존 테스트에는 결제 서비스 테스트가 아예 없다). 따라서 생성자 시그니처 변경으로 깨지는 기존 호출부는 없어야 정상이다. 그럼에도 컴파일 에러가 난다면 `./gradlew compileTestJava` 출력의 파일 경로를 그대로 따라가 `requestId` 인자를 추가한다.

- [ ] **Step 8: 커밋**

```
feat(payment): 결제 멱등성 층 2 적용 — requestId 기반 DB UNIQUE 차단
```

---

### Task 3: 층 7 — PROCESSING TTL 조정

**Files:**
- Modify: `src/main/java/com/whale/order/global/idempotency/IdempotencyService.java:19-23`(Javadoc), `:34`(상수)

**Interfaces:**
- Consumes: 없음 (상수 변경)
- Produces: 없음 (외부 시그니처 변화 없음)

- [ ] **Step 1: 상수와 Javadoc 수정**

`IdempotencyService` 의 TTL 정책 Javadoc(현재 19~23행)을 아래로 교체한다.

```java
 * <p>TTL 정책:
 * <ul>
 *   <li>PROCESSING 5분 — PG 호출 + 여유. 만료 후 새 요청 허용.
 *       층 2(payment_idempotency DB UNIQUE)가 최종 방어를 맡으므로 만료를 서두를 이유가 줄었다.
 *       만료 후 재시도가 들어와도 같은 requestId 면 DB 가 막는다.</li>
 *   <li>COMPLETED  60초 — 네트워크 재시도 보호. "동일 카트로 한 잔 더" 시나리오에서 1분 텀이면 충분.
 *       이 값을 늘리면 재주문 UX 가 바뀌므로 그대로 둔다</li>
 * </ul>
```

상수(현재 34행)를 바꾼다.

```java
    private static final Duration PROCESSING_TTL  = Duration.ofMinutes(5);
```

`RESULT_TTL` 은 `Duration.ofSeconds(60)` 그대로 둔다.

- [ ] **Step 2: 기존 멱등성 테스트가 여전히 통과하는지 확인**

```
./gradlew test --tests "com.whale.order.global.idempotency.*"
```

Expected: 전부 PASS. `IdempotencyServiceTest` 는 TTL 값을 직접 단언하지 않으므로 수정이 필요 없어야 한다. 만약 실패하면 실패한 단언이 60초에 의존하는지 확인하고, 의존한다면 5분 기준으로 고친다.

- [ ] **Step 3: 커밋**

```
refactor(idempotency): PROCESSING TTL 60초 → 5분 (층 2 도입 반영)
```

---

### Task 4: payment_idempotency 정리 배치

**Files:**
- Create: `src/main/java/com/whale/order/global/idempotency/PaymentIdempotencyCleanupJob.java`
- Test: `src/test/java/com/whale/order/global/idempotency/PaymentIdempotencyCleanupJobTest.java`

**Interfaces:**
- Consumes: `PaymentIdempotencyRepository.deleteByCreatedAtBefore(LocalDateTime)` (Task 1)
- Produces: `PaymentIdempotencyCleanupJob.cleanup()` → `void`

**이 테스트가 지키는 것:** 디스크 정리가 아니라 **정리 배치가 방어선을 먹어치우지 않는 것**이다. `Before` 를 `After` 로 잘못 쓰면 컴파일도 되고 실행도 되면서 매일 새벽 살아있는 멱등성 row를 전부 지운다 — 층 2가 조용히 무력화된다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/whale/order/global/idempotency/PaymentIdempotencyCleanupJobTest.java`

```java
package com.whale.order.global.idempotency;

import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class PaymentIdempotencyCleanupJobTest extends TestContainerBase {

    @Autowired PaymentIdempotencyCleanupJob job;
    @Autowired PaymentIdempotencyRepository repository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("cleanup 은 7일 지난 기록만 삭제하고 최근 기록은 남긴다")
    void cleanup_은_보존기간_지난_기록만_삭제() {
        // 8일 전 — 삭제 대상
        Long oldId = repository.saveAndFlush(PaymentIdempotency.of(
                1L, "66666666-6666-4666-8666-666666666666", 100L)).getId();
        생성시각_강제설정(oldId, LocalDateTime.now().minusDays(8));

        // 3일 전 — 유지
        Long recentId = repository.saveAndFlush(PaymentIdempotency.of(
                2L, "77777777-7777-4777-8777-777777777777", 200L)).getId();
        생성시각_강제설정(recentId, LocalDateTime.now().minusDays(3));

        job.cleanup();

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(oldId)).isEmpty();
        assertThat(repository.findById(recentId)).isPresent();
    }

    // created_at 은 updatable=false 라 JPA 로는 바꿀 수 없어 SQL 로 직접 세팅한다.
    private void 생성시각_강제설정(Long id, LocalDateTime ts) {
        jdbcTemplate.update("UPDATE payment_idempotency SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(ts), id);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```
./gradlew test --tests "com.whale.order.global.idempotency.PaymentIdempotencyCleanupJobTest"
```

Expected: 컴파일 실패 — `PaymentIdempotencyCleanupJob` 심볼을 찾을 수 없음.

- [ ] **Step 3: 배치 작성**

`src/main/java/com/whale/order/global/idempotency/PaymentIdempotencyCleanupJob.java`

```java
package com.whale.order.global.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 매일 새벽 3시 30분 실행. 7일 지난 멱등성 기록 삭제.
// outbox 청소(3:00)와 시간을 벌려 두 배치가 겹치지 않게 한다.
// requestId 는 결제 시도 단위로 발급돼 며칠 뒤 재사용될 일이 없으므로, 7일 지난 row 는 방어 가치가 없다.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentIdempotencyCleanupJob {

    private static final int RETENTION_DAYS = 7;

    private final PaymentIdempotencyRepository paymentIdempotencyRepository;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        long deleted = paymentIdempotencyRepository.deleteByCreatedAtBefore(cutoff);
        log.info("[멱등성] 청소 완료 deleted={} cutoff={}", deleted, cutoff);
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인**

```
./gradlew test --tests "com.whale.order.global.idempotency.PaymentIdempotencyCleanupJobTest"
```

Expected: PASS.

- [ ] **Step 5: 커밋**

```
feat(idempotency): payment_idempotency 정리 배치 추가 (7일 보존)
```

---

### Task 5: 프런트엔드와 k6 스크립트

**Files:**
- Modify: `frontend/src/pages/customer/PaymentPage.jsx`
- Modify: `k6/idempotency-test.js`
- Modify: `k6/order-load-test.js:99-108`
- Modify: `k6/stock-concurrency-test.js:116-125`

**Interfaces:**
- Consumes: `PaymentRequest.requestId` 계약 (Task 2)
- Produces: 없음

`requestId` 는 필수 필드라 이 태스크 전까지 프런트·k6 는 400을 받는다. Task 2와 함께 배포돼야 한다.

- [ ] **Step 1: 프런트 결제 페이지 수정**

`frontend/src/pages/customer/PaymentPage.jsx` 의 `const SAVED_KEY = 'wo_saved_card'` 줄 **아래**에 생성기를 추가한다.

```js
// 결제 시도 단위 멱등성 키(층 2). crypto.randomUUID 는 보안 컨텍스트(https/localhost)에서만
// 존재하므로 평문 HTTP 배포를 대비해 폴백을 둔다. 서버 검증식은 ^[0-9a-fA-F-]{36}$.
const newRequestId = () => {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  const hex = '0123456789abcdef'
  let s = ''
  for (let i = 0; i < 36; i++) {
    s += (i === 8 || i === 13 || i === 18 || i === 23) ? '-' : hex[Math.floor(Math.random() * 16)]
  }
  return s
}
```

`const [holderName,   setHolderName]   = useState('')` 줄 **아래**에 state를 추가한다.

```js
  // 마운트 시 1회 발급해 재시도 시 그대로 재사용한다. 결제 성공 시 주문 상세로 이동하며
  // 언마운트되므로 다음 결제는 자동으로 새 값을 받는다 — "같은 카트로 한 잔 더"가 막히지 않는다.
  const [requestId] = useState(() => newRequestId())
```

`processPayment({...})` 호출의 `customerRequest: customerRequest || null,` 줄 **아래**에 필드를 추가한다.

```js
        requestId,
```

- [ ] **Step 2: k6 멱등성 테스트 수정**

`k6/idempotency-test.js` 를 세 군데 고친다.

**(2-1)** `const JSON_HDR = ...` 줄 아래에 생성기를 추가한다.

```js
// k6 에는 crypto.randomUUID 가 없어 직접 만든다. 서버 검증식은 ^[0-9a-fA-F-]{36}$.
function newRequestId() {
  const hex = '0123456789abcdef';
  let s = '';
  for (let i = 0; i < 36; i++) {
    s += (i === 8 || i === 13 || i === 18 || i === 23) ? '-' : hex[Math.floor(Math.random() * 16)];
  }
  return s;
}
```

**(2-2)** `setup()` 의 `return { token, expectedAmount };` 를 아래로 바꾼다. 모든 VU가 **같은** `requestId` 를 공유해야 층 2까지 검증된다.

```js
  // 모든 VU 가 같은 requestId 를 공유 → 층 1(카트 해시)뿐 아니라 층 2(DB UNIQUE)도 함께 검증된다
  return { token, expectedAmount, requestId: newRequestId() };
```

**(2-3)** 기본 함수 시그니처와 페이로드를 바꾼다.

```js
export default function ({ token, expectedAmount, requestId }) {
  if (!token) { otherResponse.add(1); return; }

  const headers = { ...JSON_HDR, Authorization: `Bearer ${token}` };
  const res = http.post(`${BASE_URL}/api/payments`,
    JSON.stringify({
      method:          'CREDIT_CARD',
      storeId:         STORE_ID,
      orderType:       'TAKEOUT',
      expectedAmount:  expectedAmount,
      customerRequest: null,
      requestId:       requestId,
    }),
    { headers });
```

파일 상단 주석의 목적 설명(9~10행)도 갱신한다.

```js
//       SHA-256 멱등성 키가 같으므로 markProcessing 에서 1개만 처리되고 나머지는 409 로 거절.
//       requestId 도 동일하게 보내므로, 층 1이 뚫려도 층 2(DB UNIQUE)가 막아 결과는 같아야 한다.
```

- [ ] **Step 3: 나머지 k6 스크립트 수정**

두 스크립트는 정상 주문 흐름이므로 **요청마다 새 UUID** 를 만든다. 각 파일의 `const JSON_HDR = ...` 아래에 Step 2-1과 **똑같은** `newRequestId()` 함수를 넣는다.

```js
// k6 에는 crypto.randomUUID 가 없어 직접 만든다. 서버 검증식은 ^[0-9a-fA-F-]{36}$.
function newRequestId() {
  const hex = '0123456789abcdef';
  let s = '';
  for (let i = 0; i < 36; i++) {
    s += (i === 8 || i === 13 || i === 18 || i === 23) ? '-' : hex[Math.floor(Math.random() * 16)];
  }
  return s;
}
```

`k6/order-load-test.js` 의 페이로드(100~106행)를 바꾼다.

```js
    JSON.stringify({
      method:          'CREDIT_CARD',
      storeId:         STORE_ID,
      orderType:       'TAKEOUT',
      expectedAmount:  expectedAmount,
      customerRequest: null,
      requestId:       newRequestId(),
    }),
```

`k6/stock-concurrency-test.js` 의 페이로드(117~123행)를 바꾼다.

```js
    JSON.stringify({
      method:          'CREDIT_CARD',
      storeId:         STORE_ID,
      orderType:       'TAKEOUT',
      expectedAmount:  user.expectedAmount,
      customerRequest: null,
      requestId:       newRequestId(),
    }),
```

- [ ] **Step 4: 프런트 빌드 확인**

```
cd frontend && npm run build
```

Expected: 빌드 성공. k6 스크립트는 실행 중인 서버가 필요하므로 이 단계에서 돌리지 않는다 — Task 6 이후 수동 확인 항목이다.

- [ ] **Step 5: 커밋**

```
feat(payment): 결제 요청에 requestId 발급 — 프런트·k6 반영
```

---

### Task 6: 문서 갱신

**Files:**
- Modify: `docs/wiki/architecture/(추가필요)멱등redis에 추가할것.md`
- Modify: `docs/wiki/api/rest-api.md`
- Modify: `docs/wiki/architecture/redis-usage.md`
- Modify: `docs/wiki/conversations/2026-08-20.md`

**Interfaces:**
- Consumes: Task 1~5의 구현 결과
- Produces: 없음

- [ ] **Step 1: 멱등성 문서 갱신**

`docs/wiki/architecture/(추가필요)멱등redis에 추가할것.md` 를 네 군데 고친다.

**(1-1)** 3행의 상태 줄을 교체한다.

기존:
```markdown
> **상태: 현재 구조 = Redis 단일 방어 (IdempotencyService)** — 데모/포트폴리오엔 충분하지만 실서비스 이관 시 위험 지점 정리. 개선안은 층별로 나눠 기록.
```

교체:
```markdown
> **상태: 층 1(Redis) + 층 2(DB UNIQUE) 적용됨 (2026-08-20)** — 아래 6가지 시나리오는 층 1 단독 시절의 분석이다. 층 2 도입으로 시나리오 1·2·3·6은 커밋 단계에서 차단된다. 층 3·4·5는 실제 PG 연동 시점의 남은 과제.
```

**(1-2)** `### 층 2: DB UNIQUE 제약 ⭐ 우선순위 최상` 절의 SQL 블록과 불릿을 교체한다.

기존:
```sql
CREATE TABLE payment_idempotency (
    key VARCHAR(64) PRIMARY KEY,
    payment_id BIGINT,
    created_at TIMESTAMP DEFAULT NOW()
);
```

교체 (SQL 블록 + 뒤따르는 불릿 3개를 통째로):
````markdown
```sql
CREATE TABLE payment_idempotency (
    id          BIGSERIAL   PRIMARY KEY,
    member_id   BIGINT      NOT NULL,
    request_id  VARCHAR(36) NOT NULL,
    payment_id  BIGINT      NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_payment_idempotency UNIQUE (member_id, request_id)
);
```

**키는 카트 해시가 아니라 클라이언트가 발급하는 `requestId` 다.** DB UNIQUE 에는 시간 개념이 없어서,
층 1이 쓰는 카트 해시를 그대로 영구 저장하면 동일 구성 카트로는 영원히 재주문이 불가능해진다.
카트 해시는 애초에 "TTL 60초 안의 동일 카트면 실수겠지"라는 시간 윈도우 휴리스틱이라 영구 제약과 맞지 않는다.

- 결제 **성공 시에만** INSERT — 실패 결제는 row 를 남기지 않아 같은 `requestId` 로 재시도할 수 있다
- `saveAndFlush` 로 UNIQUE 위반을 즉시 감지 → `DuplicateRequestException` 으로 변환해 409 반환
- `member_id` 복합 키 — `request_id` 단독이면 타인이 같은 UUID 로 남의 결제를 막을 수 있다
- Redis 가 뚫려도 DB 레벨에서 최종 차단. 단 "늦게 막는" 방어라 실 PG 에선 층 3이 함께 필요하다
````

**(1-3)** `## 실서비스 이관 시 최소 체크리스트` 의 두 줄을 체크 처리한다.

```markdown
- [x] `payment_idempotency` 테이블 UNIQUE 제약 (층 2)
- [x] `PROCESSING_TTL` 을 PG 최대 응답 시간의 3배 이상으로 (층 7)
```

**(1-4)** `## 관련 대화` 에 한 줄 추가한다.

```markdown
- 2026-08-20: 층 2(DB UNIQUE)·층 7(TTL 5분) 구현. 두 층이 서로 다른 키를 쓰도록 설계
```

- [ ] **Step 2: API 문서 갱신**

`docs/wiki/api/rest-api.md` 의 `## Payment — /api/payments` 절, `POST /api/payments` 행 아래에 요청 필드 설명을 추가한다.

```markdown
> `POST /api/payments` 요청 필드에 `requestId`(36자 UUID, 필수)가 포함된다. 클라이언트가 결제 시도당 1회
> 발급해 재시도 시 동일 값을 재사용한다. 서버는 `(memberId, requestId)` 로 중복 결제를 최종 차단하며,
> 이미 처리된 요청이면 409를 반환한다.
```

- [ ] **Step 3: Redis 문서 갱신**

`docs/wiki/architecture/redis-usage.md` 의 `### 6. 멱등성 키 (String, SET NX EX)` 절(55~66행)에서 세 줄을 고친다.

59행 — TTL 표기:
```markdown
- TTL: PROCESSING 5분 / COMPLETED 60초
```

60행 뒤에 근거 한 줄을 추가한다:
```markdown
- 층 2 연계: 이 키가 만료된 뒤 재시도가 들어와도 `payment_idempotency` 의 UNIQUE 제약이 최종 차단하므로, PROCESSING 만료를 서두를 이유가 줄어 60초 → 5분으로 늘렸다. COMPLETED 는 "1분 뒤 같은 카트로 한 잔 더" UX 때문에 60초를 유지한다. 자세한 내용은 [멱등 Redis 단일 방어의 한계]((추가필요)멱등redis에%20추가할것.md)
```

62행 — `markProcessing` 동작 설명의 TTL 값:
```markdown
  - `markProcessing(key)` → `RBucket.setIfAbsent("__PROCESSING__", 5m)` — 단일 호출자만 true
```

63행(`saveResult ... 60초 TTL 로 set`)은 `RESULT_TTL` 이 60초 그대로이므로 **고치지 않는다**.

- [ ] **Step 4: 대화 로그 추가**

`docs/wiki/conversations/2026-08-20.md` 끝에 섹션을 추가한다.

```markdown
## 결제 멱등성 층 2 (DB UNIQUE) + 층 7 (TTL 조정) 도입

**배경**: `(추가필요)멱등redis에 추가할것.md` 가 정리한 7층 방어 중 층 2·7 적용.

### 핵심 결정 — 두 층이 서로 다른 키를 쓴다
- 기존 멱등성 키(`SHA-256(memberId+storeId+method+orderType+customerRequest+cart)`)는 진짜 멱등성 키가
  아니라 **"카트 해시 + 시간 윈도우" 휴리스틱**. 서로 다른 두 주문도 같은 해시를 가질 수 있어 TTL 60초로
  "이 정도 시간 안의 동일 카트면 실수겠지"라고 추정하는 구조.
- DB UNIQUE 에는 시간 개념이 없어 이 키를 영구 저장하면 **동일 구성 카트로 영원히 재주문 불가**.
- 키에 시간 버킷을 섞는 안은 폐기 — 키가 요청의 순수 함수가 아니게 되어 멱등성 자체가 깨지고,
  버킷 경계를 넘는 재시도는 방어를 그냥 통과한다.
- 결론: **층 1은 카트 해시(현행 유지), 층 2는 클라이언트 발급 `requestId`**. 각 층이 다른 실패 모드를
  커버하고, 프런트가 `requestId` 를 잘못 구현해도 층 1이 남아 더블클릭 방어가 유지된다.

### 구현
- `payment_idempotency` 테이블 — `UNIQUE (member_id, request_id)`. `request_id` 단독이면 타인이 같은
  UUID 로 남의 결제를 막을 수 있어 복합 키.
- **결제 성공 시에만 INSERT** — 실패 결제는 row 를 남기지 않아 같은 `requestId` 로 재시도 가능.
- `saveAndFlush` 로 UNIQUE 위반을 즉시 감지 → `DuplicateRequestException` 으로 변환해 409 반환
  (변환하지 않으면 `DataIntegrityViolationException` 이 핸들러 없이 500).
- 메트릭 `payment.idempotency.conflict{layer=redis|db}` — 두 값의 비율이 "Redis 단일 방어로 충분했는가"의 답.
- 층 7: `PROCESSING_TTL` 60초 → 5분. 층 2가 최종 방어를 맡아 만료를 서두를 이유가 줄었다. `RESULT_TTL` 은
  재주문 UX 때문에 60초 유지.
- Mock PG 성공률을 `payment.mock.success-rate` 설정값으로 분리 (테스트 프로파일 100) — 결제 성공 경로를
  결정적으로 테스트하기 위함.

### 의도적으로 뺀 것
- UNIQUE 위반 시 이전 결제를 조회해 200으로 반환하는 동작. 트랜잭션 바깥 레이어(`PaymentFacade`)가 필요해
  구조 변경이 큰 반면, 층 2는 정상 경로에선 사실상 발동하지 않는 최후 방어선이라 409 + 안내로 충분.
- 층 3(PG Idempotency-Key 헤더), 층 4(PG 상태 조회) — 실제 PG 연동 시점의 별도 작업.

### 남은 한계
- 층 2는 "늦게 막는" 방어다. 동시 요청 둘이 모두 결제 처리를 끝낸 뒤 커밋 시점에 충돌한다. Mock PG 는
  실제 청구가 없어 무해하지만, 실 PG 이관 시에는 층 3이 반드시 필요하다.
```

- [ ] **Step 5: 커밋**

```
docs: 결제 멱등성 층 2·7 도입 내용 반영
```

---

## 배포 후 수동 확인

계획 실행과 별개로, 사용자가 배포 후 확인해야 할 항목이다.

- [ ] 배포 DB에 `payment_idempotency` 테이블과 `uk_payment_idempotency` 제약이 실제로 생성됐는지 확인.
      `ddl-auto: update` 는 신규 테이블은 만들지만 기존 테이블 제약 추가는 놓칠 수 있다.
- [ ] `k6/idempotency-test.js` 실행 — 동일 `requestId` 20회 동시 전송 시 주문 1건.
- [ ] Prometheus에서 `payment_idempotency_conflict_total{layer="db"}` 노출 확인.
      평상시 0이어야 정상이고, 값이 오르면 Redis 층이 실제로 뚫리고 있다는 신호다.
