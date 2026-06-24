# Member — 회원

> 회원가입/로그인/인증/관리자 회원 관리. JWT 기반 인증 + OAuth2(Kakao) 소셜 로그인.

**디렉토리**: `src/main/java/com/whale/order/domain/member/`

## 구성

| 분류 | 파일 |
|------|------|
| Entity | `Member`, `MemberRole`, `AuthProvider` |
| Controller | `AuthController` (로그인/회원가입/리프레시), `MemberController` (마이페이지), `AdminMemberController` |
| Service | `MemberService` |
| Repository | `MemberRepository` |

## 핵심 플로우

- **회원가입**: `SignUpRequest` → 이메일 중복 체크 → BCrypt 해시 → 저장
- **로그인**: `LoginRequest` → JWT Access + Refresh 발급 → Refresh는 `RefreshTokenService` (Redis) 보관
- **리프레시**: `RefreshRequest` → Redis 토큰 검증 → 새 Access 발급
- **소셜 로그인**: OAuth2 (Kakao) → `AuthProvider` 로 식별

## 관련 문서

- 인증 토큰 저장소: [Redis 활용처 — 리프레시 토큰](../architecture/redis-usage.md#2-리프레시-토큰-string)
