# Member — 회원

> 회원가입/로그인/인증/관리자 회원 관리. JWT 기반 인증 + OAuth2(Kakao) 소셜 로그인.

**디렉토리**: `src/main/java/com/whale/order/domain/member/`

## 구성

| 분류 | 파일 |
|------|------|
| Entity | `Member`, `MemberRole`, `AuthProvider` |
| Controller | `AuthController` (로그인/회원가입/리프레시), `MemberController` (마이페이지·탈퇴), `AdminMemberController` |
| Service | `MemberService` |
| Repository | `MemberRepository` |

## 핵심 플로우

- **회원가입**: `SignUpRequest` → 이메일 중복 체크 → BCrypt 해시 → 저장
- **로그인**: `LoginRequest` → JWT Access + Refresh 발급 → Refresh는 `RefreshTokenService` (Redis) 보관
- **리프레시**: `RefreshRequest` → Redis 토큰 검증 → 새 Access 발급
- **소셜 로그인**: OAuth2 (Kakao) → `AuthProvider` 로 식별

## 회원 탈퇴

`DELETE /api/members/me` — CUSTOMER 만 가능하다. 점주·관리자는 매장이 고아가 되므로 403 이며,
매장 이관·폐점은 별도 업무 프로세스로 처리한다.

관리자 강제 삭제(`DELETE /api/admin/members/{id}`)는 **제거했다.** 역할 검사가 없어 OWNER·ADMIN 까지
삭제됐고, UI 는 "복구 불가"로 안내하면서 실제로는 소프트 삭제였다. 제재가 필요하면 역할 변경으로 처리한다.

### 차단 조건

| 상황 | 응답 |
|------|------|
| OWNER · ADMIN | 403 (`SecurityConfig` URL 규칙 + 서비스 계층 이중 방어) |
| LOCAL 인데 비밀번호 누락/불일치 | 400 |
| 진행 중 주문(PENDING · PREPARING) 보유 | 409 `WithdrawNotAllowedException` |

진행 중 주문을 남기고 탈퇴하면 SSE 로 상태를 받을 수도 환불받을 수도 없어 원천 차단한다.
KAKAO 회원은 `password` 가 null 이라 비밀번호 검증 대상이 아니며 요청 body 를 생략할 수 있다.

### 익명화 — `Member.withdraw()`

개인정보만 덮어쓰고 `orders` · `payment` row 와 FK 는 보존한다. 매장의 매출 집계가 틀어지지 않는다.

| 컬럼 | 값 |
|------|-----|
| `userId` | `deleted_{memberId}` — unique 슬롯 반납, 같은 아이디로 재가입 가능 |
| `providerId` | KAKAO 만 `deleted_{memberId}` — 같은 카카오 계정으로 재가입 가능 |
| `name` | `탈퇴한 회원` (`nullable = false` 라 null 불가) |
| `nickname` · `phone` · `password` | `null` |
| `role` · `provider` | 유지 (통계·구분 목적) |
| `withdrawReason` · `withdrawnAt` | 기록 (아래) |

`nickname` 이 null 이면 `OrderResponse` 의 기존 fallback 이 `name` 을 집어 주문 목록에 `탈퇴한 회원` 으로 표시된다.

### 탈퇴 사유

`WithdrawReason` enum 으로 받는다. **자유 텍스트를 허용하지 않는 이유**는, 익명화로 개인정보를 지우는 옆에서
사유 컬럼에 연락처·이름이 새로 쌓이면 익명화의 의미가 사라지기 때문이다.

```
NOT_USING · INCONVENIENT · PRICE · SERVICE_QUALITY · PRIVACY · OTHER
```

**선택 입력이다.** 사유를 안 골랐다고 탈퇴를 막으면 나가기 어렵게 만드는 다크패턴이 되므로,
미응답이면 `withdrawReason` 이 null 로 남고 탈퇴는 그대로 진행된다.

`withdrawnAt` 은 별도 컬럼이다. `BaseEntity.updatedAt` 은 모든 수정에 갱신돼 탈퇴 시각으로 쓸 수 없다.

별도 `member_withdrawal` 테이블을 두지 않은 이유 — 재가입을 새 회원으로 처리하므로 한 `member` row 는
평생 한 번만 탈퇴한다(1:1). 익명화 정책이라 row 가 사라지지도 않아 분리 보관의 이점이 없다.
재가입 시 기존 회원을 복구하는 정책으로 바꾸거나 컬럼이 4~5개로 늘면 그때 분리한다.

탈퇴 후 Redis 의 리프레시 토큰과 장바구니를 정리한다. DB 트랜잭션 롤백 대상이 아니지만 실패해도 무해하다 —
토큰이 남아도 아래 인증·리프레시 차단에 걸리고, 장바구니는 TTL 24시간으로 자동 소멸한다.

### 탈퇴 회원을 막는 지점

`Member` 에는 `@SQLRestriction` 이 **없다.** 익명화로 개인정보가 이미 지워지므로 전역으로 숨길 이유가 없고,
오히려 `orders → member` 연관까지 끊어 **탈퇴 회원의 주문이 매장·어드민 목록에서 통째로 사라지는** 문제가 있었다
(`JOIN FETCH o.member` 가 INNER JOIN 이라 행 자체가 빠졌다). 대신 아래 지점에서만 명시적으로 막는다.

| 지점 | 목적 |
|------|------|
| `MemberRepository.findByUserIdAndIsDeletedFalse` | 자체 로그인 |
| `MemberRepository.findByProviderAndProviderIdAndIsDeletedFalse` | 카카오 로그인 |
| `CustomUserDetailsService.loadUserByMemberId` | JWT 인증 — 잔여 access token 즉시 무효화 |
| `MemberService.findActiveById` | 리프레시 토큰 재발급 |
| `MemberRepository.searchByRoleAndKeyword` · `findAllWithFilters` | 어드민 목록 |

`existsByUserId` 는 필터를 걸지 않는다. DB unique 제약과 검사 범위를 일치시켜야
"중복 체크는 통과하고 제약 위반으로 500" 이 나지 않는다.

`JwtAuthenticationFilter` 는 `UsernameNotFoundException` 을 삼키고 인증만 건너뛴다.
필터에서 던지면 `@RestControllerAdvice` 가 잡지 못해 401 대신 500 이 되기 때문이다.

## 관련 문서

- 인증 토큰 저장소: [Redis 활용처 — 리프레시 토큰](../architecture/redis-usage.md#2-리프레시-토큰-string)
