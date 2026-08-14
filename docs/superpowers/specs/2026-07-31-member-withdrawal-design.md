# 회원 탈퇴 기능 — 설계

작성일: 2026-07-31

## 배경

`Member` 에는 이미 소프트 삭제 인프라가 있다 — `Member.softDelete()`, `@SQLRestriction("is_deleted = false")`,
관리자 강제 삭제 `DELETE /api/admin/members/{memberId}`. 없는 것은 **회원 본인이 직접 하는 탈퇴**다.

기능을 붙이는 과정에서 기존 소프트 삭제 구현의 결함 3가지가 드러났고, 이 설계에 함께 포함한다.
관리자 강제 삭제는 검토 결과 제거하기로 했다(결정 사항 참조).

| # | 결함 | 현재 증상 |
|---|------|-----------|
| 1 | `existsByUserId` 가 `@SQLRestriction` 때문에 삭제 회원을 못 봄 | 중복 체크는 통과하고 DB unique 제약에서 500 |
| 2 | `JOIN FETCH o.member` + `@SQLRestriction` = INNER JOIN 필터 | 삭제 회원의 주문이 어드민/매장 목록에서 **조용히 사라짐**. 주문 상세는 "존재하지 않는 주문" |
| 3 | `UsernameNotFoundException` 이 서블릿 필터에서 발생 | 삭제 회원의 access token 으로 요청 시 401 이 아니라 **500** |

## 결정 사항

| 항목 | 결정 |
|------|------|
| 개인정보 | **즉시 익명화**. 주문/결제 row 와 FK 는 전부 보존 |
| 진행 중 주문 | **탈퇴 차단** (409). 자동 취소하지 않음 |
| OWNER / ADMIN | **셀프 탈퇴 불가** (403). 매장 이관/폐점은 별도 업무 프로세스 |
| 본인 확인 | LOCAL 은 비밀번호 재입력 필수, KAKAO 는 JWT 인증만 |
| `@SQLRestriction` | **제거**하고 필요한 지점에만 명시적 필터 |
| 관리자 회원 삭제 | **기능 자체를 제거** (아래) |

### 관리자 회원 삭제를 제거하는 이유

`DELETE /api/admin/members/{memberId}` 와 `AdminMemberPage` 의 삭제 버튼을 제거한다.

- **역할 검사가 없다.** OWNER 셀프 탈퇴는 매장이 고아가 되는 이유로 403 으로 막기로 했는데,
  어드민 경로에는 그 검사가 없어 OWNER·ADMIN 이 클릭 한 번에 삭제된다. 방금 막은 구멍이 옆문으로 뚫려 있다.
- **UI 문구가 사실과 다르다.** "삭제된 데이터는 복구할 수 없습니다" 라고 안내하지만 실제로는 소프트 삭제다.
- **용도가 없다.** 개인정보 파기 대행·악성 회원 제재는 운영 조직을 전제하고, 테스트 계정 정리는
  k6 가 계정을 생성만 하고 삭제하지 않으므로 해당이 없다.

유지하려면 역할 차단·UI 문구 수정·익명화 통일이 함께 필요해 안 쓰는 기능에 범위가 붙는다.
제재가 필요하면 이미 있는 역할 변경(`MemberUpdateRequest`)으로 처리한다.
조회·생성·수정·비밀번호 초기화는 그대로 유지한다 — k6 부하 테스트가 `POST /api/admin/members` 로 계정을 만든다.

### `@SQLRestriction` 을 제거하는 이유

`@SQLRestriction` 은 두 가지 일을 겸하고 있었다 — (1) 탈퇴 회원의 로그인·인증 차단,
(2) 탈퇴 회원을 모든 조회에서 제거. 익명화를 도입하면 (2)는 불필요해진다. 이름이 이미 `탈퇴한 회원` 이기 때문이다.
그런데 (2)가 `orders → member` 연관까지 끊어 위 결함 2를 만든다.

대안으로 `@NotFound(IGNORE)` 를 검토했으나 Hibernate 가 LAZY 를 무시하고 즉시 로딩을 강제해
주문 목록마다 N+1 이 생긴다. TPS/Latency 측정이 프로젝트의 핵심 목표이므로 채택하지 않았다.

## API

```
DELETE /api/members/me
Authorization: Bearer {accessToken}
Content-Type: application/json

{ "password": "..." }     // LOCAL 필수, KAKAO 는 body 자체를 생략 가능

200 { "success": true, "message": "탈퇴가 완료됐습니다", "data": null }
```

KAKAO 회원은 보낼 값이 없으므로 컨트롤러는 `@RequestBody(required = false) WithdrawRequest` 로 받고,
서비스는 `request == null` 을 "비밀번호 미제출"로 취급한다. LOCAL 이면 그 시점에 400 이다.

| 상황 | 상태 코드 | 구현 |
|------|-----------|------|
| OWNER / ADMIN | 403 | `SecurityConfig` URL 규칙 + 서비스 계층 이중 방어 |
| LOCAL 인데 비밀번호 누락/불일치 | 400 | `IllegalArgumentException` (기존 핸들러) |
| 진행 중 주문(PENDING·PREPARING) 존재 | 409 | `WithdrawNotAllowedException` (신규) |

403 은 정상적으로는 `SecurityConfig` 에서 걸린다. 서비스 계층 검사는 URL 규칙이 나중에 느슨해질 때를 대비한
이중 방어이며, Spring Security 의 `AccessDeniedException` 을 던지고 `GlobalExceptionHandler` 에 403 매핑을 추가한다.
(서비스에서 던진 예외는 필터가 아닌 컨트롤러 경로라 `@RestControllerAdvice` 가 잡는다.)

## 익명화 규칙 — `Member.withdraw()`

| 컬럼 | 값 | 근거 |
|------|-----|------|
| `userId` | `deleted_{memberId}` | `@Column(unique = true)` 슬롯 반납 → 같은 아이디로 재가입 가능 |
| `providerId` | KAKAO 만 `deleted_{memberId}`, LOCAL 은 `null` 유지 | `uq_member_provider(provider, provider_id)` 슬롯 반납 → 카카오 재가입 가능 |
| `name` | `탈퇴한 회원` | 주문 목록 표시용. `@Column(nullable = false)` 라 null 불가 |
| `nickname` | `null` | — |
| `phone` | `null` | 개인정보 파기 |
| `password` | `null` | 개인정보 파기 |
| `isDeleted` | `true` | — |
| `role`, `provider` | 변경 없음 | `nullable = false` 이고 통계/구분 목적으로 유지 |

`nickname` 을 `null` 로 두면 `OrderResponse.from()` 의 기존 fallback
(`nickname != null ? nickname : name`)이 `name` 을 집어 `탈퇴한 회원` 을 출력한다.
**`OrderResponse` 는 수정하지 않는다.**

## 서비스 흐름 — `MemberService.withdraw(memberId, request)`

```
1. findById(memberId)
2. role != CUSTOMER               → 403
3. provider == LOCAL              → passwordEncoder.matches() 검증, 불일치 시 400
4. 진행 중 주문 존재               → 409
5. member.withdraw()                 [DB 트랜잭션]
6. refreshTokenService.delete(id)    [Redis]
7. cartService.clearCart(id)         [Redis]
```

6·7 은 DB 트랜잭션 롤백 대상이 아니지만 실패해도 무해하다.
리프레시 토큰이 남아도 아래 두 겹의 차단에 걸리고, 장바구니는 TTL 24시간으로 자동 소멸한다.
따라서 별도 보상 처리를 두지 않는다.

### 리프레시 경로 차단

`MemberService.refresh()` 는 Redis 토큰 대조 후 `findById(memberId)` 로 회원을 다시 읽는다.
`@SQLRestriction` 이 사라지면 이 조회가 **탈퇴 회원도 그대로 반환**하므로, 6 번이 실패해 토큰이 남아있으면
탈퇴 회원이 새 토큰을 재발급받을 수 있다. 발급된 access token 은 `CustomUserDetailsService` 에서
막히지만, 재발급 자체가 성공하는 것은 옳지 않다.

→ `MemberService` 의 private `findById()` 옆에 탈퇴 여부를 확인하는 경로를 두고 `refresh()` 에서 사용한다.
`withdraw()`·어드민 관리 메서드는 탈퇴 회원도 읽을 수 있어야 하므로 기존 `findById()` 를 유지한다.

## 변경 파일

### 신규 (3)

| 파일 | 내용 |
|------|------|
| `domain/member/dto/WithdrawRequest.java` | `record WithdrawRequest(String password)` |
| `global/exception/WithdrawNotAllowedException.java` | `RuntimeException` 상속 |
| `src/test/java/.../member/service/MemberWithdrawTest.java` | `TestContainerBase` 기반 |

### 수정 (14)

| 파일 | 변경 |
|------|------|
| `member/entity/Member.java` | `@SQLRestriction` 제거, `softDelete()` → `withdraw()` 로 교체 |
| `member/service/MemberService.java` | `withdraw()` 추가, `deleteMember()` 제거, `refresh()` 에 탈퇴 회원 차단, `CartService` 의존성 추가 |
| `member/controller/MemberController.java` | `DELETE /api/members/me` |
| `member/controller/AdminMemberController.java` | `DELETE /api/admin/members/{memberId}` 제거 |
| `frontend/src/api/member.js` | `deleteMember` 제거 |
| `frontend/src/pages/admin/AdminMemberPage.jsx` | 삭제 버튼·`handleDelete`·import·주석 제거 |
| `member/repository/MemberRepository.java` | `findByUserId` → `findByUserIdAndIsDeletedFalse`, `findByProviderAndProviderId` → `...AndIsDeletedFalse`, JPQL 2개에 `AND m.isDeleted = false` |
| `global/auth/CustomUserDetailsService.java` | `loadUserByUsername`·`loadUserByMemberId` 에서 탈퇴 회원 거부 |
| `global/auth/jwt/JwtAuthenticationFilter.java` | `UsernameNotFoundException` 을 삼키고 인증만 건너뜀 → 500 대신 401 |
| `global/config/SecurityConfig.java` | `.requestMatchers(DELETE, "/api/members/me").hasRole("CUSTOMER")` |
| `global/exception/GlobalExceptionHandler.java` | `WithdrawNotAllowedException` → 409, `AccessDeniedException` → 403 |
| `order/repository/OrderRepository.java` | `existsByMemberMemberIdAndStatusIn(Long, List<OrderStatus>)` |
| `store/service/StoreService.java` | 이름 바뀐 리포지토리 메서드로 호출부 수정 |
| `stock/service/StockDemoService.java` | 동일 |

`existsByUserId` 는 **변경하지 않는다.** `@SQLRestriction` 이 사라지면서 DB unique 제약과
정확히 같은 범위를 보게 되고, 이것이 결함 1의 수정이다.

### 문서

- `docs/wiki/domains/member.md` — 탈퇴 플로우, 익명화 규칙
- `docs/wiki/api/rest-api.md` — `DELETE /api/members/me` 추가, 관리자 회원 CRUD 행에서 DELETE 제거

## 기존 삭제 데이터 — 해당 없음

관리자 삭제 기능으로 이미 `is_deleted = true` 가 된 회원이 있었다면 실명·전화번호가 익명화되지 않은 채
남아있어, `@SQLRestriction` 제거와 동시에 과거 주문이 **실명으로** 노출되고 점유 중인 `user_id` 때문에
결함 1 의 500 도 남았을 것이다. 이를 정리하는 백필 SQL 을 검토했으나,
**해당 데이터가 없음을 확인해 불필요하다.**

배포 전 확인용 쿼리 (0 건이어야 한다):

```sql
SELECT member_id, user_id, name, provider FROM member WHERE is_deleted = true;
```

dev 와 prod 는 별개의 DB 이므로 배포 대상 환경에서 각각 확인한다.
0 건이 아니면 위 익명화 규칙과 같은 `UPDATE` 를 1 회 수행한 뒤 배포한다.

## 스키마 변경

**없다.** `is_deleted` 컬럼은 이미 존재하고 인덱스·제약 변경도 없다.

## 부수 효과

`StoreRepository` 의 `JOIN FETCH s.owner` 쿼리 3개가 `@SQLRestriction` 제거의 영향을 받아,
삭제된 OWNER 소유 매장이 어드민 목록에 다시 나타난다. 다만 현재 삭제된 회원이 없고
OWNER 셀프 탈퇴를 막으며 관리자 삭제도 제거하므로, **실제로 발생할 경로가 없다.**

## 테스트

`TestContainerBase` (PostgreSQL + Redis) 기반 통합 테스트.

**정상 경로**
- 탈퇴 성공 → `isDeleted = true`, `userId`/`name`/`nickname`/`phone` 익명화 검증
- 탈퇴 성공 → 리프레시 토큰(Redis) 삭제, 장바구니(Redis) 삭제 검증
- KAKAO 회원은 비밀번호 없이 탈퇴 성공

**차단 경로**
- LOCAL 비밀번호 불일치 → 400
- PENDING 주문 보유 → 409
- PREPARING 주문 보유 → 409
- COMPLETED·CANCELLED 주문만 보유 → 탈퇴 성공
- OWNER → 403

**회귀 (기존 결함)**
- 탈퇴 후 동일 `userId` 로 재가입 성공 (결함 1)
- 탈퇴 회원의 과거 주문이 어드민 목록에 `탈퇴한 회원` 으로 조회됨 (결함 2)
- 탈퇴 후 기존 access token 으로 API 호출 → 401 (결함 3)
- 탈퇴 후 남아있는 리프레시 토큰으로 `/api/auth/refresh` 호출 → 실패
