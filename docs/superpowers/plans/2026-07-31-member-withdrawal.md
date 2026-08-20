# 회원 탈퇴 기능 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 회원이 직접 탈퇴하는 `DELETE /api/members/me` 를 추가하고, 그 과정에서 드러난 기존 소프트 삭제 구현의 결함 3가지를 함께 고친다.

**Architecture:** 탈퇴는 개인정보만 익명화하고 주문·결제 row 와 FK 는 보존한다. `Member` 의 `@SQLRestriction("is_deleted = false")` 를 제거하고 탈퇴 회원을 실제로 막아야 하는 지점(로그인 2곳, JWT 인증, 리프레시, 어드민 목록 2곳)에만 명시적 필터를 건다. 관리자 강제 삭제 기능은 역할 검사가 없어 OWNER/ADMIN 이 우회 삭제되므로 제거한다.

**Tech Stack:** Java 21, Spring Boot 3.x, Spring Security(JWT), Spring Data JPA, PostgreSQL, Redis, JUnit 5 + AssertJ + Testcontainers + MockMvc

**Spec:** `docs/superpowers/specs/2026-07-31-member-withdrawal-design.md`

## Global Constraints

- 코드 주석은 한국어, 변수·메서드명은 영어 camelCase (CLAUDE.md)
- 익명화 값은 스펙 고정: `userId` = `deleted_{memberId}`, `name` = `탈퇴한 회원`, `nickname`·`phone`·`password` = `null`, `providerId` 는 KAKAO 만 `deleted_{memberId}`, `role`·`provider` 는 변경하지 않음
- 진행 중 주문 판정 상태는 `OrderStatus.PENDING`, `OrderStatus.PREPARING` 두 개
- 셀프 탈퇴 가능 역할은 `MemberRole.CUSTOMER` 뿐
- 스키마 변경 금지 — `is_deleted` 컬럼은 이미 존재하며 인덱스·제약을 추가하지 않는다
- DB 마이그레이션 없음 (`ddl-auto` 는 dev/prod `update`, 기본 `validate`)
- 통합 테스트는 `TestContainerBase` 를 상속하고 `@SpringBootTest`, `@ActiveProfiles("test")`, `@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})` 를 함께 붙인다. `KafkaConfig` 가 컨텍스트 로드 시 자동 등록되어 `@EmbeddedKafka` 없이는 브로커 연결 오류로 컨텍스트가 뜨지 않는다.
- 테스트 명령은 PowerShell 기준 `.\gradlew.bat` 사용

---

## 진행 상태 (2026-08-03 — Task 1~8 전부 완료)

전체 테스트 **87개 중 86개 통과.** 신규 23개 전부 통과 (기존 64 → 87).

유일한 실패는 `OrderApplicationTests.contextLoads()` 로, **이번 작업과 무관한 기존 문제다.**
이 클래스는 `@SpringBootTest` 만 붙어있고 `@ActiveProfiles("test")` 도 `TestContainerBase` 상속도 없어
Testcontainers 대신 로컬 `localhost:5432` 에 붙으려다 `Connection refused` 로 실패한다.
고치려면 `TestContainerBase` 를 상속시키고 `@ActiveProfiles("test")` 를 붙이면 된다.

**모든 변경이 커밋되지 않은 상태다.** 커밋은 사용자가 직접 한다.

### 계획 대비 차이

- `AdminMemberPage.module.css` 의 `.deleteBtn` 스타일 제거 (계획 외 — 삭제 버튼이 사라져 쓰는 곳이 없어짐)
- Task 3 테스트에서 주문에 `OrderItem` 을 붙였다. `findByIdWithDetails` 의
  `JOIN FETCH o.orderItems` 가 INNER JOIN 이라 아이템이 없으면 `isPresent()` 가 실패한다.
  (계획서 원문 코드에는 빠져 있었다)

---

### Task 1: 관리자 회원 삭제 기능 제거 ✅ 완료

역할 검사가 없어 OWNER·ADMIN 이 클릭 한 번에 삭제되는 경로를 없앤다. 순수 삭제 작업이므로 새 테스트를 쓰지 않고 기존 테스트 전체가 통과하는 것으로 검증한다. 다음 Task 에서 `softDelete()` 를 `withdraw()` 로 교체하려면 이 Task 가 먼저 끝나야 한다(유일한 호출자가 사라져야 하므로).

**Files:**
- Modify: `src/main/java/com/whale/order/domain/member/controller/AdminMemberController.java:68-76`
- Modify: `src/main/java/com/whale/order/domain/member/service/MemberService.java:136-139`
- Modify: `frontend/src/api/member.js:17`
- Modify: `frontend/src/pages/admin/AdminMemberPage.jsx:2,29,122-131,204`

**Interfaces:**
- Consumes: 없음
- Produces: `MemberService.deleteMember(Long)` 와 `Member.softDelete()` 의 호출자가 모두 사라진다. Task 2 가 `softDelete()` 를 제거할 수 있게 된다.

- [ ] **Step 1: 백엔드 엔드포인트 제거**

`AdminMemberController.java` 에서 아래 블록 전체를 삭제한다.

```java
    /**
     * 회원을 삭제한다. 실제로는 소프트 삭제(is_deleted=true)로 처리되어 주문·결제 등 FK 참조 데이터는 보존된다.
     */
    @Operation(summary = "회원 삭제")
    @DeleteMapping("/{memberId}")
    public ResponseEntity<ApiResponse<Void>> deleteMember(@PathVariable Long memberId) {
        memberService.deleteMember(memberId);
        return ResponseEntity.ok(ApiResponse.ok("회원이 삭제됐습니다", null));
    }
```

삭제 후 `DeleteMapping` import 가 남아있다면(와일드카드 import 이면 그대로 둔다) 정리한다.

- [ ] **Step 2: 서비스 메서드 제거**

`MemberService.java` 에서 아래 블록을 삭제한다.

```java
    @Transactional
    public void deleteMember(Long memberId) {
        findById(memberId).softDelete();
    }
```

- [ ] **Step 3: 프론트엔드 API 함수 제거**

`frontend/src/api/member.js:17` 의 아래 줄을 삭제한다.

```javascript
export const deleteMember   = (memberId) => client.delete(`/admin/members/${memberId}`)
```

- [ ] **Step 4: 프론트엔드 삭제 버튼 제거**

`AdminMemberPage.jsx` 에서 네 곳을 수정한다.

import 문(2행)에서 `deleteMember` 를 뺀다.

```javascript
import { getMembers, createMember, updateMember, resetPassword } from '../../api/member'
```

상단 주석(29행)에서 아래 줄을 삭제한다.

```javascript
 * - 회원 삭제: 확인 후 영구 삭제
```

`handleDelete` 함수(122-131행) 전체를 삭제한다.

```javascript
  // ── 삭제 ────────────────────────────────────────────────────────
  const handleDelete = async (member) => {
    if (!window.confirm(`"${member.name}" 회원을 삭제하시겠습니까?\n삭제된 데이터는 복구할 수 없습니다.`)) return
    try {
      await deleteMember(member.memberId)
      load()
    } catch (err) {
      alert(err.response?.data?.message || '삭제에 실패했습니다')
    }
  }
```

테이블의 삭제 버튼(204행)을 삭제해 액션 셀에 수정 버튼만 남긴다.

```jsx
                    <td className={styles.actions}>
                      <button className={styles.editBtn} onClick={() => openEdit(m)}>수정</button>
                    </td>
```

- [ ] **Step 5: 컴파일 및 기존 테스트 전체 통과 확인**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL. `deleteMember` 를 참조하던 코드가 남아있으면 컴파일 에러가 나므로 잔여 참조가 함께 검출된다.

- [ ] **Step 6: 프론트엔드 빌드 확인**

Run: `cd frontend; npm run build`
Expected: 빌드 성공. `deleteMember` 미정의 참조가 남아있으면 실패한다.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/whale/order/domain/member frontend/src/api/member.js frontend/src/pages/admin/AdminMemberPage.jsx
git commit -m "refactor: 관리자 회원 삭제 기능 제거"
```

---

### Task 2: Member.withdraw() 익명화 ✅ 완료

`softDelete()` 를 익명화까지 수행하는 `withdraw()` 로 교체한다.

**Files:**
- Modify: `src/main/java/com/whale/order/domain/member/entity/Member.java:99-101`
- Test: `src/test/java/com/whale/order/domain/member/entity/MemberWithdrawEntityTest.java` (create)

**Interfaces:**
- Consumes: Task 1 이 `softDelete()` 의 마지막 호출자를 제거한 상태
- Produces: `Member.withdraw()` — 반환값 없음. 호출 후 `getUserId()` = `"deleted_" + memberId`, `getName()` = `"탈퇴한 회원"`, `getNickname()`·`getPhone()`·`getPassword()` = `null`, `isDeleted()` = `true`. KAKAO 회원은 `getProviderId()` = `"deleted_" + memberId`. Task 6 이 호출한다.

- [ ] **Step 1: 실패하는 테스트 작성**

Create `src/test/java/com/whale/order/domain/member/entity/MemberWithdrawEntityTest.java`:

```java
package com.whale.order.domain.member.entity;

import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Member.withdraw() 익명화 규칙 검증.
 *
 * <p>withdraw() 는 memberId 를 익명화 값에 사용하므로 영속화 이후에만 의미가 있다.
 * 따라서 순수 단위 테스트가 아니라 저장 후 호출하는 통합 테스트로 작성한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class MemberWithdrawEntityTest extends TestContainerBase {

    @Autowired private MemberRepository memberRepository;

    @Test
    @DisplayName("LOCAL 회원 탈퇴 시 개인정보가 익명화되고 userId 가 반납된다")
    void LOCAL_회원_익명화() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .nickname("철수").phone("010-1234-5678")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        Long id = member.getMemberId();

        // when
        member.withdraw();

        // then
        assertThat(member.getUserId()).isEqualTo("deleted_" + id);
        assertThat(member.getPassword()).isNull();
        assertThat(member.getName()).isEqualTo("탈퇴한 회원");
        assertThat(member.getNickname()).isNull();
        assertThat(member.getPhone()).isNull();
        assertThat(member.isDeleted()).isTrue();
        // 역할·프로바이더는 통계 목적으로 유지한다
        assertThat(member.getRole()).isEqualTo(MemberRole.CUSTOMER);
        assertThat(member.getProvider()).isEqualTo(AuthProvider.LOCAL);
        // LOCAL 은 providerId 가 원래 null 이므로 그대로 null
        assertThat(member.getProviderId()).isNull();
    }

    @Test
    @DisplayName("KAKAO 회원 탈퇴 시 providerId 도 반납돼 같은 카카오 계정으로 재가입할 수 있다")
    void KAKAO_회원_providerId_반납() {
        // given
        Member member = memberRepository.save(Member.builder()
                .name("김카카오").nickname("카카오닉")
                .provider(AuthProvider.KAKAO).providerId("1234567890")
                .role(MemberRole.CUSTOMER).build());
        Long id = member.getMemberId();

        // when
        member.withdraw();

        // then
        assertThat(member.getProviderId()).isEqualTo("deleted_" + id);
        assertThat(member.getUserId()).isEqualTo("deleted_" + id);
        assertThat(member.isDeleted()).isTrue();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.domain.member.entity.MemberWithdrawEntityTest"`
Expected: 컴파일 실패 — `cannot find symbol: method withdraw()`

- [ ] **Step 3: withdraw() 구현**

`Member.java` 의 `softDelete()` 를 아래로 교체한다.

```java
    /**
     * 회원 탈퇴 — 개인정보를 익명화하고 삭제 상태로 전환한다.
     *
     * <p>userId 와 providerId 는 unique 제약 슬롯을 반납해 같은 계정으로 재가입할 수 있게 한다.
     * name 은 nullable=false 이면서 주문 목록 표시에 쓰이므로 고정 문구로 대체한다.
     * nickname 을 null 로 두면 OrderResponse 의 기존 fallback 이 name 을 집어 "탈퇴한 회원"을 출력한다.
     */
    public void withdraw() {
        this.userId = "deleted_" + this.memberId;
        this.password = null;
        this.name = "탈퇴한 회원";
        this.nickname = null;
        this.phone = null;
        if (this.provider == AuthProvider.KAKAO) {
            this.providerId = "deleted_" + this.memberId;
        }
        this.isDeleted = true;
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.domain.member.entity.MemberWithdrawEntityTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/whale/order/domain/member/entity/Member.java src/test/java/com/whale/order/domain/member/entity/MemberWithdrawEntityTest.java
git commit -m "feat: Member.withdraw() 익명화 추가"
```

---

### Task 3: @SQLRestriction 제거 + 명시적 필터 ← 여기부터 재개

`@SQLRestriction` 이 `orders → member` 연관까지 끊어 탈퇴 회원의 주문이 목록에서 사라지는 결함 2를 고친다. 애노테이션 제거와 필터 추가는 한 커밋에 함께 들어가야 한다 — 따로 하면 그 사이에 탈퇴 회원이 로그인 가능한 상태가 된다.

**Files:**
- Modify: `src/main/java/com/whale/order/domain/member/entity/Member.java:9,25` (애노테이션 + import 제거)
- Modify: `src/main/java/com/whale/order/domain/member/repository/MemberRepository.java`
- Modify: `src/main/java/com/whale/order/global/auth/CustomUserDetailsService.java:20,27`
- Modify: `src/main/java/com/whale/order/domain/member/service/MemberService.java:75`
- Modify: `src/main/java/com/whale/order/global/auth/oauth2/KakaoOAuth2UserService.java:39`
- Modify: `src/main/java/com/whale/order/domain/store/service/StoreService.java:105`
- Modify: `src/main/java/com/whale/order/domain/stock/service/StockDemoService.java:32`
- Test: `src/test/java/com/whale/order/domain/member/service/MemberWithdrawalVisibilityTest.java` (create)

**Interfaces:**
- Consumes: `Member.withdraw()` (Task 2)
- Produces:
  - `MemberRepository.findByUserIdAndIsDeletedFalse(String userId)` → `Optional<Member>`
  - `MemberRepository.findByProviderAndProviderIdAndIsDeletedFalse(AuthProvider provider, String providerId)` → `Optional<Member>`
  - `MemberRepository.findByUserId` 와 `findByProviderAndProviderId` 는 더 이상 존재하지 않는다
  - `MemberRepository.existsByUserId(String)` 는 이름·시그니처 그대로 유지되지만, 필터가 사라져 탈퇴 회원까지 포함해 검사한다 (결함 1 수정)
  - `MemberRepository.findById(Long)` 는 탈퇴 회원도 반환한다 — Task 5 가 이 사실에 의존한다

- [ ] **Step 1: 실패하는 테스트 작성**

Create `src/test/java/com/whale/order/domain/member/service/MemberWithdrawalVisibilityTest.java`:

```java
package com.whale.order.domain.member.service;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.order.entity.OrderType;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @SQLRestriction 제거 후의 조회 가시성 검증.
 *
 * <p>탈퇴 회원은 로그인 경로에서만 숨겨지고, 주문 이력·findById 에서는 익명화된 상태로 조회돼야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class MemberWithdrawalVisibilityTest extends TestContainerBase {

    @Autowired private MemberRepository memberRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;

    @Test
    @DisplayName("탈퇴 회원은 로그인 조회에서 제외된다")
    void 탈퇴회원_로그인조회_제외() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        assertThat(memberRepository.findByUserIdAndIsDeletedFalse("chulsoo")).isPresent();

        // when
        member.withdraw();
        memberRepository.saveAndFlush(member);

        // then: 원래 아이디로는 찾히지 않는다
        assertThat(memberRepository.findByUserIdAndIsDeletedFalse("chulsoo")).isEmpty();
    }

    @Test
    @DisplayName("탈퇴 KAKAO 회원은 카카오 로그인 조회에서 제외된다")
    void 탈퇴회원_카카오조회_제외() {
        // given
        Member member = memberRepository.save(Member.builder()
                .name("김카카오").provider(AuthProvider.KAKAO).providerId("1234567890")
                .role(MemberRole.CUSTOMER).build());

        // when
        member.withdraw();
        memberRepository.saveAndFlush(member);

        // then
        assertThat(memberRepository.findByProviderAndProviderIdAndIsDeletedFalse(
                AuthProvider.KAKAO, "1234567890")).isEmpty();
    }

    @Test
    @DisplayName("탈퇴 후 같은 아이디로 재가입할 수 있다 — 중복 검사가 탈퇴 회원까지 본다")
    void 탈퇴후_같은아이디_재가입_가능() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());

        // when
        member.withdraw();
        memberRepository.saveAndFlush(member);

        // then: 아이디 슬롯이 반납돼 중복이 아니다
        assertThat(memberRepository.existsByUserId("chulsoo")).isFalse();

        // 실제로 같은 아이디로 저장해도 제약 위반이 없다
        Member rejoined = memberRepository.saveAndFlush(Member.builder()
                .userId("chulsoo").password("encoded2").name("김철수2")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        assertThat(rejoined.getMemberId()).isNotEqualTo(member.getMemberId());
    }

    @Test
    @DisplayName("탈퇴 회원의 과거 주문이 JOIN FETCH 목록에 '탈퇴한 회원'으로 남는다")
    void 탈퇴회원_주문_목록에_남음() {
        // given
        Member owner = memberRepository.save(Member.builder()
                .name("점주").provider(AuthProvider.LOCAL).role(MemberRole.OWNER).build());
        Member customer = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수").nickname("철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        Store store = storeRepository.save(Store.builder()
                .owner(owner).name("테스트 매장").postalCode("12345")
                .address("서울시 강남구 테스트로 1")
                .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(21, 0)).build());
        menuRepository.save(Menu.builder()
                .name("아메리카노").basePrice(4500L).category(MenuCategory.BEVERAGE).build());
        Orders order = orderRepository.save(Orders.builder()
                .member(customer).store(store)
                .totalPrice(4500L).orderType(OrderType.TAKEOUT).build());

        // when
        customer.withdraw();
        memberRepository.saveAndFlush(customer);

        // then: 주문이 사라지지 않고 익명화된 이름으로 조회된다
        assertThat(orderRepository.findAllWithDetails())
                .extracting(o -> o.getMember().getName())
                .containsExactly("탈퇴한 회원");
        assertThat(orderRepository.findByIdWithDetails(order.getOrderId())).isPresent();
    }

    @Test
    @DisplayName("findById 는 탈퇴 회원도 반환한다")
    void findById_탈퇴회원_반환() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        Long id = member.getMemberId();

        // when
        member.withdraw();
        memberRepository.saveAndFlush(member);

        // then
        assertThat(memberRepository.findById(id)).isPresent();
        assertThat(memberRepository.findById(id).orElseThrow().isDeleted()).isTrue();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.domain.member.service.MemberWithdrawalVisibilityTest"`
Expected: 컴파일 실패 — `cannot find symbol: method findByUserIdAndIsDeletedFalse`

- [ ] **Step 3: Member 엔티티에서 @SQLRestriction 제거**

`Member.java` 25행의 애노테이션과 9행의 import 를 삭제한다.

```java
// 삭제할 줄 (9행)
import org.hibernate.annotations.SQLRestriction;

// 삭제할 줄 (25행)
@SQLRestriction("is_deleted = false")
```

클래스 선언부는 아래처럼 남는다.

```java
@Entity
@Table(
    name = "member",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_member_provider",
        columnNames = {"provider", "provider_id"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {
```

- [ ] **Step 4: MemberRepository 에 명시적 필터 적용**

`MemberRepository.java` 전체를 아래로 교체한다. 파생 쿼리 대신 `@Query` 를 쓰는 이유는 `isDeleted` 라는 boolean 필드명이 `IsDeletedFalse` 로 파싱될 때 프로퍼티 해석이 모호해질 수 있어서다.

```java
package com.whale.order.domain.member.repository;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // 자체 로그인 ID로 조회 - 탈퇴 회원은 로그인할 수 없어야 하므로 제외한다
    @Query("SELECT m FROM Member m WHERE m.userId = :userId AND m.isDeleted = false")
    Optional<Member> findByUserIdAndIsDeletedFalse(@Param("userId") String userId);

    // 카카오 소셜 로그인 회원 조회 - 탈퇴 회원 제외 (재로그인 시 새 회원으로 가입된다)
    @Query("SELECT m FROM Member m WHERE m.provider = :provider AND m.providerId = :providerId " +
           "AND m.isDeleted = false")
    Optional<Member> findByProviderAndProviderIdAndIsDeletedFalse(@Param("provider") AuthProvider provider,
                                                                  @Param("providerId") String providerId);

    // 자체 로그인 ID 중복 확인 - 탈퇴 회원까지 포함해야 DB unique 제약과 범위가 일치한다.
    // 탈퇴 시 userId 를 deleted_{memberId} 로 바꿔 슬롯을 반납하므로 재가입을 막지 않는다.
    boolean existsByUserId(String userId);

    // 특정 역할 + 아이디/이름 키워드 검색 (최대 20건)
    @Query("SELECT m FROM Member m WHERE m.role = :role AND m.isDeleted = false AND (" +
           "LOWER(COALESCE(m.userId, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<Member> searchByRoleAndKeyword(@Param("role") MemberRole role,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);

    // 전체 회원 목록 - 키워드/역할 필터 (어드민 회원 관리)
    @Query("SELECT m FROM Member m WHERE m.isDeleted = false AND " +
           "(:keyword IS NULL OR :keyword = '' OR " +
           " LOWER(COALESCE(m.userId, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(COALESCE(m.nickname, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:role IS NULL OR m.role = :role) " +
           "ORDER BY m.createdAt DESC")
    List<Member> findAllWithFilters(@Param("keyword") String keyword,
                                    @Param("role") MemberRole role);
}
```

- [ ] **Step 5: CustomUserDetailsService 에 탈퇴 회원 차단 추가**

`CustomUserDetailsService.java` 의 두 메서드를 아래로 교체한다.

```java
    // 자체 로그인 시 Spring Security가 호출 (userId로 조회)
    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        Member member = memberRepository.findByUserIdAndIsDeletedFalse(userId)
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 아이디입니다: " + userId));
        return new CustomUserDetails(member);
    }

    // JWT 필터에서 호출 (토큰에서 꺼낸 memberId로 조회)
    // 탈퇴 회원은 남아있는 access token 으로도 인증되지 않아야 하므로 여기서 걸러낸다.
    public UserDetails loadUserByMemberId(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 회원입니다: " + memberId));
        return new CustomUserDetails(member);
    }
```

- [ ] **Step 6: 나머지 호출부 4곳 수정**

`MemberService.java:75` (login):

```java
        Member member = memberRepository.findByUserIdAndIsDeletedFalse(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다"));
```

`KakaoOAuth2UserService.java:39`:

```java
        Member member = memberRepository.findByProviderAndProviderIdAndIsDeletedFalse(AuthProvider.KAKAO, providerId)
```

`StoreService.java:105`:

```java
        Member owner = memberRepository.findByUserIdAndIsDeletedFalse(request.ownerUserId())
```

`StockDemoService.java:32`:

```java
        Member owner = memberRepository.findByUserIdAndIsDeletedFalse("demo-owner")
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.domain.member.service.MemberWithdrawalVisibilityTest"`
Expected: PASS (5 tests)

- [ ] **Step 8: 전체 테스트로 회귀 확인**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL. 이름이 바뀐 리포지토리 메서드의 잔여 호출부가 있으면 컴파일 에러로 검출된다.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/whale/order src/test/java/com/whale/order/domain/member/service/MemberWithdrawalVisibilityTest.java
git commit -m "fix: @SQLRestriction 제거하고 로그인·인증 경로에만 명시적 필터 적용"
```

---

### Task 4: JwtAuthenticationFilter 500 → 401

`loadUserByMemberId` 가 던지는 `UsernameNotFoundException` 은 서블릿 필터에서 발생해 `@RestControllerAdvice` 가 잡지 못하고 500 이 된다. 인증만 건너뛰어 Security 의 `authenticationEntryPoint` 가 401 을 내도록 고친다.

**Files:**
- Modify: `src/main/java/com/whale/order/global/auth/jwt/JwtAuthenticationFilter.java:27-42`
- Test: `src/test/java/com/whale/order/global/auth/jwt/WithdrawnMemberAuthTest.java` (create)

**Interfaces:**
- Consumes: `CustomUserDetailsService.loadUserByMemberId(Long)` 가 탈퇴 회원에 대해 `UsernameNotFoundException` 을 던진다 (Task 3)
- Produces: 탈퇴 회원의 유효한 access token 으로 인증 필요 API 호출 시 HTTP 401

- [ ] **Step 1: 실패하는 테스트 작성**

Create `src/test/java/com/whale/order/global/auth/jwt/WithdrawnMemberAuthTest.java`:

```java
package com.whale.order.global.auth.jwt;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴 회원의 잔여 access token 처리 검증.
 *
 * <p>필터에서 던진 예외는 @RestControllerAdvice 가 잡지 못해 500 이 된다.
 * 인증을 건너뛰고 SecurityConfig 의 authenticationEntryPoint 가 401 을 내야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class WithdrawnMemberAuthTest extends TestContainerBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JwtProvider jwtProvider;

    @Test
    @DisplayName("탈퇴 회원의 유효한 access token 으로 요청하면 401 을 받는다")
    void 탈퇴회원_토큰_401() throws Exception {
        // given: 정상 회원에게 토큰을 발급한 뒤 탈퇴시킨다
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        member.withdraw();
        memberRepository.saveAndFlush(member);

        // when & then: 토큰 자체는 유효하지만 회원이 없으므로 401
        mockMvc.perform(get("/api/members/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("정상 회원의 access token 으로는 내 정보를 조회할 수 있다")
    void 정상회원_토큰_200() throws Exception {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("younghee").password("encoded").name("김영희")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        // when & then
        mockMvc.perform(get("/api/members/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.global.auth.jwt.WithdrawnMemberAuthTest"`
Expected: `탈퇴회원_토큰_401` FAIL — `Status expected:<401> but was:<500>`. `정상회원_토큰_200` 은 PASS.

- [ ] **Step 3: 필터에서 예외를 삼키도록 수정**

`JwtAuthenticationFilter.java` 의 `doFilterInternal` 을 아래로 교체하고 `UsernameNotFoundException` import 를 추가한다.

```java
import org.springframework.security.core.userdetails.UsernameNotFoundException;
```

```java
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null && jwtProvider.validateToken(token)) {
            Long memberId = jwtProvider.getMemberId(token);
            try {
                UserDetails userDetails = userDetailsService.loadUserByMemberId(memberId);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (UsernameNotFoundException e) {
                // 탈퇴한 회원의 잔여 토큰 — 필터에서 예외를 던지면 @RestControllerAdvice 가 잡지 못해 500 이 된다.
                // 인증하지 않고 통과시켜 SecurityConfig 의 authenticationEntryPoint 가 401 을 내도록 한다.
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.global.auth.jwt.WithdrawnMemberAuthTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/whale/order/global/auth/jwt/JwtAuthenticationFilter.java src/test/java/com/whale/order/global/auth/jwt/WithdrawnMemberAuthTest.java
git commit -m "fix: 탈퇴 회원 토큰 요청이 500 대신 401 을 반환하도록 수정"
```

---

### Task 5: refresh() 에서 탈퇴 회원 차단

`@SQLRestriction` 이 사라져 `findById` 가 탈퇴 회원을 반환하므로, Redis 토큰 삭제가 실패해 리프레시 토큰이 남아있으면 탈퇴자가 새 토큰을 재발급받을 수 있다.

**Files:**
- Modify: `src/main/java/com/whale/order/domain/member/service/MemberService.java:175-195`
- Test: `src/test/java/com/whale/order/domain/member/service/MemberRefreshTest.java` (create)

**Interfaces:**
- Consumes: `MemberRepository.findById(Long)` 가 탈퇴 회원도 반환한다 (Task 3)
- Produces: `MemberService.findActiveById(Long)` (private) — 탈퇴 회원이면 `IllegalArgumentException`

- [ ] **Step 1: 실패하는 테스트 작성**

Create `src/test/java/com/whale/order/domain/member/service/MemberRefreshTest.java`:

```java
package com.whale.order.domain.member.service;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.global.auth.RefreshTokenService;
import com.whale.order.global.auth.jwt.JwtProvider;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 탈퇴 회원의 리프레시 토큰 재발급 차단 검증.
 *
 * <p>Redis 토큰 삭제가 실패해 토큰이 남아있는 상황을 재현하기 위해
 * 탈퇴 처리 후 토큰을 다시 저장한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class MemberRefreshTest extends TestContainerBase {

    @Autowired private MemberService memberService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private JwtProvider jwtProvider;

    @Test
    @DisplayName("탈퇴 회원의 리프레시 토큰으로는 재발급받을 수 없다")
    void 탈퇴회원_리프레시_차단() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        Long id = member.getMemberId();
        String refreshToken = jwtProvider.generateRefreshToken(id);

        member.withdraw();
        memberRepository.saveAndFlush(member);

        // Redis 정리가 실패해 토큰이 남아있는 상황을 재현
        refreshTokenService.save(id, refreshToken);

        // when & then
        assertThatThrownBy(() -> memberService.refresh(refreshToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 회원입니다");
    }

    @Test
    @DisplayName("정상 회원은 리프레시로 새 토큰을 발급받는다")
    void 정상회원_리프레시_성공() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("younghee").password("encoded").name("김영희").nickname("영희")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        String refreshToken = jwtProvider.generateRefreshToken(member.getMemberId());
        refreshTokenService.save(member.getMemberId(), refreshToken);

        // when
        var response = memberService.refresh(refreshToken);

        // then
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.domain.member.service.MemberRefreshTest"`
Expected: `탈퇴회원_리프레시_차단` FAIL — 예외가 발생하지 않고 토큰이 정상 발급된다.

- [ ] **Step 3: findActiveById 추가 후 refresh() 에서 사용**

`MemberService.java` 의 private `findById` 아래에 `findActiveById` 를 추가한다.

```java
    private Member findById(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다"));
    }

    /**
     * 탈퇴하지 않은 회원만 조회한다.
     *
     * <p>Member 에서 @SQLRestriction 을 제거해 findById 가 탈퇴 회원도 반환하므로,
     * 토큰 재발급처럼 살아있는 회원만 대상이어야 하는 경로에서는 이쪽을 쓴다.
     */
    private Member findActiveById(Long memberId) {
        return memberRepository.findById(memberId)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다"));
    }
```

`refresh()` 192행의 호출을 바꾼다.

```java
        Member member = findActiveById(memberId);
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.domain.member.service.MemberRefreshTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/whale/order/domain/member/service/MemberService.java src/test/java/com/whale/order/domain/member/service/MemberRefreshTest.java
git commit -m "fix: 탈퇴 회원의 리프레시 토큰 재발급 차단"
```

---

### Task 6: MemberService.withdraw() 탈퇴 로직

**Files:**
- Create: `src/main/java/com/whale/order/domain/member/dto/WithdrawRequest.java`
- Create: `src/main/java/com/whale/order/global/exception/WithdrawNotAllowedException.java`
- Modify: `src/main/java/com/whale/order/domain/order/repository/OrderRepository.java`
- Modify: `src/main/java/com/whale/order/domain/member/service/MemberService.java`
- Modify: `src/main/java/com/whale/order/global/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/whale/order/domain/member/service/MemberWithdrawTest.java` (create)

**Interfaces:**
- Consumes: `Member.withdraw()` (Task 2), `CartService.clearCart(Long)`, `RefreshTokenService.delete(Long)`
- Produces:
  - `WithdrawRequest(String password)` — record, `null` 허용
  - `WithdrawNotAllowedException(String message)` — `RuntimeException` 상속, 409 매핑
  - `MemberService.withdraw(Long memberId, WithdrawRequest request)` → `void`
  - `OrderRepository.existsByMember_MemberIdAndStatusIn(Long memberId, List<OrderStatus> statuses)` → `boolean`
  - Task 7 이 컨트롤러에서 `withdraw` 를 호출한다

- [ ] **Step 1: 실패하는 테스트 작성**

Create `src/test/java/com/whale/order/domain/member/service/MemberWithdrawTest.java`:

```java
package com.whale.order.domain.member.service;

import com.whale.order.domain.member.dto.WithdrawRequest;
import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.entity.OrderType;
import com.whale.order.domain.order.entity.Orders;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.global.auth.RefreshTokenService;
import com.whale.order.global.exception.WithdrawNotAllowedException;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회원 탈퇴 서비스 통합 테스트.
 *
 * <p>검증 범위: 익명화, Redis 정리(리프레시 토큰·장바구니),
 * 차단 조건 3가지(비밀번호 불일치 / 진행 중 주문 / 역할).
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class MemberWithdrawTest extends TestContainerBase {

    @Autowired private MemberService memberService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    private Store store;

    @BeforeEach
    void setUpFixtures() {
        Member owner = memberRepository.save(Member.builder()
                .name("점주").provider(AuthProvider.LOCAL).role(MemberRole.OWNER).build());
        store = storeRepository.save(Store.builder()
                .owner(owner).name("테스트 매장").postalCode("12345")
                .address("서울시 강남구 테스트로 1")
                .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(21, 0)).build());
        menuRepository.save(Menu.builder()
                .name("아메리카노").basePrice(4500L).category(MenuCategory.BEVERAGE).build());
    }

    @Test
    @DisplayName("LOCAL 회원이 비밀번호를 맞게 입력하면 익명화되고 Redis 데이터가 정리된다")
    void LOCAL_탈퇴_성공() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        Long id = member.getMemberId();
        // 정리 대상이 실제로 존재해야 삭제됐는지 확인할 수 있다.
        // 장바구니는 CartService.addItem 대신 Redis 키를 직접 심어 Menu·Stock 픽스처를 피한다.
        refreshTokenService.save(id, "dummy-refresh-token");
        redisTemplate.opsForHash().put("cart:" + id, "dummy-item", "{}");
        assertThat(redisTemplate.hasKey("cart:" + id)).isTrue();

        // when
        memberService.withdraw(id, new WithdrawRequest("pw1234!"));

        // then: 익명화
        Member found = memberRepository.findById(id).orElseThrow();
        assertThat(found.isDeleted()).isTrue();
        assertThat(found.getUserId()).isEqualTo("deleted_" + id);
        assertThat(found.getName()).isEqualTo("탈퇴한 회원");
        assertThat(found.getNickname()).isNull();
        assertThat(found.getPhone()).isNull();

        // then: Redis 정리 (cart: 접두사는 CartService.CART_KEY_PREFIX 와 동일)
        assertThat(refreshTokenService.get(id)).isNull();
        assertThat(redisTemplate.hasKey("cart:" + id)).isFalse();
    }

    @Test
    @DisplayName("KAKAO 회원은 비밀번호 없이 탈퇴할 수 있다")
    void KAKAO_탈퇴_성공() {
        // given
        Member member = memberRepository.save(Member.builder()
                .name("김카카오").provider(AuthProvider.KAKAO).providerId("1234567890")
                .role(MemberRole.CUSTOMER).build());
        Long id = member.getMemberId();

        // when: body 자체가 없는 요청을 재현
        memberService.withdraw(id, null);

        // then
        Member found = memberRepository.findById(id).orElseThrow();
        assertThat(found.isDeleted()).isTrue();
        assertThat(found.getProviderId()).isEqualTo("deleted_" + id);
    }

    @Test
    @DisplayName("LOCAL 회원이 비밀번호를 틀리면 탈퇴되지 않는다")
    void 비밀번호_불일치_차단() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        Long id = member.getMemberId();

        // when & then
        assertThatThrownBy(() -> memberService.withdraw(id, new WithdrawRequest("wrong-password")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비밀번호가 올바르지 않습니다");
        assertThat(memberRepository.findById(id).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    @DisplayName("LOCAL 회원이 비밀번호를 보내지 않으면 탈퇴되지 않는다")
    void 비밀번호_누락_차단() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        Long id = member.getMemberId();

        // when & then
        assertThatThrownBy(() -> memberService.withdraw(id, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비밀번호가 올바르지 않습니다");
    }

    @Test
    @DisplayName("PENDING 주문이 있으면 탈퇴할 수 없다")
    void 진행중_PENDING_주문_차단() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        주문_생성(member, OrderStatus.PENDING);

        // when & then
        assertThatThrownBy(() -> memberService.withdraw(member.getMemberId(), new WithdrawRequest("pw1234!")))
                .isInstanceOf(WithdrawNotAllowedException.class)
                .hasMessageContaining("진행 중인 주문");
    }

    @Test
    @DisplayName("PREPARING 주문이 있으면 탈퇴할 수 없다")
    void 진행중_PREPARING_주문_차단() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        주문_생성(member, OrderStatus.PREPARING);

        // when & then
        assertThatThrownBy(() -> memberService.withdraw(member.getMemberId(), new WithdrawRequest("pw1234!")))
                .isInstanceOf(WithdrawNotAllowedException.class)
                .hasMessageContaining("진행 중인 주문");
    }

    @Test
    @DisplayName("완료·취소된 주문만 있으면 탈퇴할 수 있다")
    void 완료된_주문만_있으면_탈퇴_성공() {
        // given
        Member member = 고객_생성("chulsoo", "pw1234!");
        주문_생성(member, OrderStatus.COMPLETED);
        주문_생성(member, OrderStatus.CANCELLED);

        // when
        memberService.withdraw(member.getMemberId(), new WithdrawRequest("pw1234!"));

        // then
        assertThat(memberRepository.findById(member.getMemberId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("OWNER 는 셀프 탈퇴할 수 없다")
    void OWNER_셀프탈퇴_차단() {
        // given
        Member owner = memberRepository.save(Member.builder()
                .userId("owner1").password(passwordEncoder.encode("pw1234!")).name("점주2")
                .provider(AuthProvider.LOCAL).role(MemberRole.OWNER).build());

        // when & then
        assertThatThrownBy(() -> memberService.withdraw(owner.getMemberId(), new WithdrawRequest("pw1234!")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(memberRepository.findById(owner.getMemberId()).orElseThrow().isDeleted()).isFalse();
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────

    private Member 고객_생성(String userId, String rawPassword) {
        return memberRepository.save(Member.builder()
                .userId(userId).password(passwordEncoder.encode(rawPassword))
                .name("김철수").nickname("철수").phone("010-1234-5678")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
    }

    private void 주문_생성(Member member, OrderStatus status) {
        Orders order = orderRepository.save(Orders.builder()
                .member(member).store(store)
                .totalPrice(4500L).orderType(OrderType.TAKEOUT).build());
        if (status == OrderStatus.PREPARING) {
            order.startPreparing();
        } else if (status == OrderStatus.COMPLETED) {
            order.startPreparing();
            order.complete();
        } else if (status == OrderStatus.CANCELLED) {
            order.cancel();
        }
        orderRepository.save(order);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.domain.member.service.MemberWithdrawTest"`
Expected: 컴파일 실패 — `WithdrawRequest`, `WithdrawNotAllowedException`, `memberService.withdraw` 를 찾을 수 없음

- [ ] **Step 3: WithdrawRequest 생성**

Create `src/main/java/com/whale/order/domain/member/dto/WithdrawRequest.java`:

```java
package com.whale.order.domain.member.dto;

/**
 * 회원 탈퇴 요청.
 *
 * <p>LOCAL 회원은 비밀번호 재확인이 필요하지만 KAKAO 회원은 보낼 값이 없어
 * body 자체를 생략할 수 있다. 따라서 필드에 @NotBlank 를 걸지 않고 서비스에서 검증한다.
 */
public record WithdrawRequest(String password) {
}
```

- [ ] **Step 4: WithdrawNotAllowedException 생성**

Create `src/main/java/com/whale/order/global/exception/WithdrawNotAllowedException.java`:

```java
package com.whale.order.global.exception;

/**
 * 현재 상태에서 탈퇴할 수 없을 때 발생 (예: 진행 중인 주문 보유).
 * 클라이언트가 조건을 해소한 뒤 재시도할 수 있으므로 409 Conflict 로 매핑한다.
 */
public class WithdrawNotAllowedException extends RuntimeException {
    public WithdrawNotAllowedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: OrderRepository 에 진행 중 주문 조회 추가**

`OrderRepository.java` 의 `countByStatus` 아래에 추가하고 `java.util.List` import 가 있는지 확인한다(이미 있다).

```java
    // 회원 탈퇴 가능 여부 판정 - 접수/제조 중 주문이 하나라도 있으면 탈퇴를 막는다
    boolean existsByMember_MemberIdAndStatusIn(Long memberId, List<OrderStatus> statuses);
```

- [ ] **Step 6: GlobalExceptionHandler 에 409·403 매핑 추가**

`GlobalExceptionHandler.java` 의 `handleDuplicateRequestException` 아래에 두 핸들러를 추가하고 import 를 더한다.

```java
import org.springframework.security.access.AccessDeniedException;
```

```java
    // 탈퇴 불가 상태 (진행 중인 주문 보유 등) — 조건 해소 후 재시도 가능
    @ExceptionHandler(WithdrawNotAllowedException.class)
    public ResponseEntity<ApiResponse<Void>> handleWithdrawNotAllowedException(WithdrawNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(e.getMessage()));
    }

    // 서비스 계층에서 거부한 권한 없는 요청 (예: OWNER·ADMIN 의 셀프 탈퇴)
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(e.getMessage()));
    }
```

- [ ] **Step 7: MemberService.withdraw() 구현**

`MemberService.java` 에 import 와 의존성을 추가한다.

```java
import com.whale.order.domain.cart.service.CartService;
import com.whale.order.domain.order.entity.OrderStatus;
import com.whale.order.domain.order.repository.OrderRepository;
import com.whale.order.global.exception.WithdrawNotAllowedException;
import org.springframework.security.access.AccessDeniedException;
```

```java
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final OrderRepository orderRepository;
    private final CartService cartService;
```

`changePassword` 아래에 `withdraw` 를 추가한다.

```java
    /**
     * 회원 본인 탈퇴.
     *
     * <p>개인정보만 익명화하고 주문·결제 row 와 FK 는 보존해 매장의 매출 집계를 지킨다.
     * 점주·관리자는 매장이 고아가 되므로 셀프 탈퇴 대상이 아니다.
     */
    @Transactional
    public void withdraw(Long memberId, WithdrawRequest request) {
        Member member = findById(memberId);

        // SecurityConfig 의 URL 규칙과 이중 방어 — 규칙이 느슨해져도 여기서 막힌다
        if (member.getRole() != MemberRole.CUSTOMER) {
            throw new AccessDeniedException("점주·관리자는 직접 탈퇴할 수 없습니다. 관리자에게 문의해주세요");
        }

        // LOCAL 회원만 비밀번호 재확인 (KAKAO 는 password 가 null 이라 검증 대상이 아니다)
        if (member.getProvider() == AuthProvider.LOCAL) {
            String password = (request == null) ? null : request.password();
            if (password == null || password.isBlank()
                    || !passwordEncoder.matches(password, member.getPassword())) {
                throw new IllegalArgumentException("비밀번호가 올바르지 않습니다");
            }
        }

        // 진행 중 주문을 남긴 채 탈퇴하면 SSE 도 환불도 받을 수 없어 원천 차단한다
        boolean hasOngoingOrder = orderRepository.existsByMember_MemberIdAndStatusIn(
                memberId, List.of(OrderStatus.PENDING, OrderStatus.PREPARING));
        if (hasOngoingOrder) {
            throw new WithdrawNotAllowedException("진행 중인 주문이 있습니다. 주문을 완료하거나 취소한 뒤 다시 시도해주세요");
        }

        member.withdraw();

        // Redis 정리는 트랜잭션 롤백 대상이 아니지만 실패해도 무해하다.
        // 토큰이 남아도 CustomUserDetailsService·findActiveById 에서 막히고,
        // 장바구니는 TTL 24시간으로 자동 소멸한다.
        refreshTokenService.delete(memberId);
        cartService.clearCart(memberId);
    }
```

- [ ] **Step 8: 테스트 통과 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.domain.member.service.MemberWithdrawTest"`
Expected: PASS (8 tests)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/whale/order src/test/java/com/whale/order/domain/member/service/MemberWithdrawTest.java
git commit -m "feat: 회원 탈퇴 서비스 로직 추가"
```

---

### Task 7: DELETE /api/members/me 엔드포인트 + SecurityConfig

**Files:**
- Modify: `src/main/java/com/whale/order/domain/member/controller/MemberController.java`
- Modify: `src/main/java/com/whale/order/global/config/SecurityConfig.java:70-71`
- Test: `src/test/java/com/whale/order/domain/member/controller/MemberWithdrawApiTest.java` (create)

**Interfaces:**
- Consumes: `MemberService.withdraw(Long, WithdrawRequest)` (Task 6), `JwtProvider.generateAccessToken(Long, MemberRole)`
- Produces: `DELETE /api/members/me` — 200 / 400 / 403 / 409

- [ ] **Step 1: 실패하는 테스트 작성**

Create `src/test/java/com/whale/order/domain/member/controller/MemberWithdrawApiTest.java`:

```java
package com.whale.order.domain.member.controller;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.global.auth.jwt.JwtProvider;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DELETE /api/members/me 엔드투엔드 검증.
 *
 * <p>SecurityConfig 의 역할 제한이 실제로 403 을 내는지, 탈퇴 직후 같은 토큰이
 * 401 로 막히는지를 HTTP 계층에서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class MemberWithdrawApiTest extends TestContainerBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("CUSTOMER 가 비밀번호를 맞게 보내면 200 과 함께 탈퇴된다")
    void 탈퇴_성공_200() throws Exception {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password(passwordEncoder.encode("pw1234!")).name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        // when & then
        mockMvc.perform(delete("/api/members/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw1234!\"}"))
                .andExpect(status().isOk());

        assertThat(memberRepository.findById(member.getMemberId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("비밀번호가 틀리면 400 을 받는다")
    void 비밀번호_불일치_400() throws Exception {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password(passwordEncoder.encode("pw1234!")).name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        // when & then
        mockMvc.perform(delete("/api/members/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("OWNER 는 SecurityConfig 에서 403 으로 막힌다")
    void OWNER_403() throws Exception {
        // given
        Member owner = memberRepository.save(Member.builder()
                .userId("owner1").password(passwordEncoder.encode("pw1234!")).name("점주")
                .provider(AuthProvider.LOCAL).role(MemberRole.OWNER).build());
        String token = jwtProvider.generateAccessToken(owner.getMemberId(), owner.getRole());

        // when & then
        mockMvc.perform(delete("/api/members/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw1234!\"}"))
                .andExpect(status().isForbidden());

        assertThat(memberRepository.findById(owner.getMemberId()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    @DisplayName("탈퇴 직후 같은 토큰으로 내 정보를 조회하면 401 을 받는다")
    void 탈퇴후_같은토큰_401() throws Exception {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password(passwordEncoder.encode("pw1234!")).name("김철수")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        String token = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());

        mockMvc.perform(delete("/api/members/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw1234!\"}"))
                .andExpect(status().isOk());

        // when & then
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/members/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.domain.member.controller.MemberWithdrawApiTest"`
Expected: 전부 FAIL — `DELETE /api/members/me` 가 없어 405 Method Not Allowed

- [ ] **Step 3: 컨트롤러에 엔드포인트 추가**

`MemberController.java` 에 import 를 추가한다.

```java
import com.whale.order.domain.member.dto.WithdrawRequest;
```

`changePassword` 아래에 메서드를 추가한다.

```java
    /**
     * 회원 본인이 탈퇴한다. 개인정보는 익명화되고 주문 이력은 보존된다.
     */
    @Operation(summary = "회원 탈퇴",
               description = "LOCAL 회원은 비밀번호 재확인 필요. 진행 중인 주문이 있으면 탈퇴할 수 없다")
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> withdraw(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) WithdrawRequest request) {
        Long memberId = Long.parseLong(userDetails.getUsername());
        memberService.withdraw(memberId, request);
        return ResponseEntity.ok(ApiResponse.ok("탈퇴가 완료됐습니다", null));
    }
```

- [ ] **Step 4: SecurityConfig 에 역할 제한 추가**

`SecurityConfig.java` 에 import 를 추가한다.

```java
import org.springframework.http.HttpMethod;
```

70행 `.requestMatchers("/api/admin/**").hasRole("ADMIN")` 과 71행 `.anyRequest().authenticated()` 사이에 넣는다.

```java
                        // 나머지 관리자 API(메뉴, 매장, 이벤트, 회원관리 등) - ADMIN 전용
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // 회원 탈퇴는 고객 본인만 - 점주는 매장이 고아가 되므로 셀프 탈퇴 불가
                        .requestMatchers(HttpMethod.DELETE, "/api/members/me").hasRole("CUSTOMER")
                        .anyRequest().authenticated()
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `.\gradlew.bat test --tests "com.whale.order.domain.member.controller.MemberWithdrawApiTest"`
Expected: PASS (4 tests)

- [ ] **Step 6: 전체 테스트 통과 확인**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/whale/order src/test/java/com/whale/order/domain/member/controller/MemberWithdrawApiTest.java
git commit -m "feat: DELETE /api/members/me 회원 탈퇴 엔드포인트 추가"
```

---

### Task 8: wiki 문서 갱신

CLAUDE.md 의 문서 참조 정책에 따라 wiki 를 코드와 동기화한다.

**Files:**
- Modify: `docs/wiki/domains/member.md`
- Modify: `docs/wiki/api/rest-api.md:17`

**Interfaces:**
- Consumes: Task 1-7 의 최종 동작
- Produces: 없음 (문서)

- [ ] **Step 1: rest-api.md 갱신**

17행의 관리자 회원 CRUD 행에서 DELETE 를 뺀다.

```markdown
| GET / POST / PUT | `/api/admin/members[/{id}]` | 관리자 회원 조회·생성·수정 (삭제 없음 — 탈퇴는 본인만) |
```

`/api/members/me` 섹션에 탈퇴를 추가한다.

```markdown
| DELETE | `/api/members/me` | 회원 탈퇴 (CUSTOMER 전용, LOCAL 은 비밀번호 재확인) |
```

- [ ] **Step 2: member.md 갱신**

파일을 열어 탈퇴 절을 추가한다. 기존 문서 구조·말투에 맞춰 배치하되 아래 내용을 담는다.

```markdown
## 회원 탈퇴

`DELETE /api/members/me` — CUSTOMER 만 가능하다. 점주·관리자는 매장이 고아가 되므로 403 이며,
매장 이관·폐점은 별도 업무 프로세스로 처리한다.

### 차단 조건

| 상황 | 응답 |
|------|------|
| OWNER · ADMIN | 403 |
| LOCAL 인데 비밀번호 누락/불일치 | 400 |
| 진행 중 주문(PENDING · PREPARING) 보유 | 409 |

진행 중 주문을 남기고 탈퇴하면 SSE 로 상태를 받을 수도, 환불받을 수도 없어 원천 차단한다.

### 익명화

개인정보만 덮어쓰고 `orders` · `payment` row 와 FK 는 보존한다. 매장의 매출 집계가 틀어지지 않는다.

| 컬럼 | 값 |
|------|-----|
| `userId` | `deleted_{memberId}` (unique 슬롯 반납 → 같은 아이디로 재가입 가능) |
| `providerId` | KAKAO 만 `deleted_{memberId}` (카카오 재가입 가능) |
| `name` | `탈퇴한 회원` |
| `nickname` · `phone` · `password` | `null` |
| `role` · `provider` | 유지 |

`nickname` 이 `null` 이면 `OrderResponse` 의 fallback 이 `name` 을 집어 주문 목록에 `탈퇴한 회원`으로 표시된다.

### 탈퇴 회원을 막는 지점

`Member` 에는 `@SQLRestriction` 이 없다. 익명화로 개인정보가 이미 지워지므로 모든 조회에서 숨길 필요가 없고,
오히려 `orders → member` 연관까지 끊어 주문이 목록에서 사라지는 문제가 있었다. 대신 아래 5곳에서만 명시적으로 막는다.

- `MemberRepository.findByUserIdAndIsDeletedFalse` — 자체 로그인
- `MemberRepository.findByProviderAndProviderIdAndIsDeletedFalse` — 카카오 로그인
- `CustomUserDetailsService.loadUserByMemberId` — JWT 인증 (잔여 access token 무력화)
- `MemberService.findActiveById` — 리프레시 토큰 재발급
- `MemberRepository.searchByRoleAndKeyword` · `findAllWithFilters` — 어드민 목록
```

- [ ] **Step 3: Commit**

```bash
git add docs/wiki
git commit -m "docs: 회원 탈퇴 기능 wiki 반영"
```

---

## 완료 조건

- [ ] `.\gradlew.bat test` 전체 통과
- [ ] `cd frontend; npm run build` 성공
- [ ] 신규 테스트 23개 통과 (Task 2: 2, Task 3: 5, Task 4: 2, Task 5: 2, Task 6: 8, Task 7: 4)
- [ ] `git grep -n "SQLRestriction"` 결과가 비어있음
- [ ] `git grep -n "softDelete\|deleteMember"` 결과가 비어있음
