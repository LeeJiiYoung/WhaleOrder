# REST API

> WhaleOrder 백엔드가 노출하는 HTTP 엔드포인트 목록 (컨트롤러 기준)

도메인별 상세 동작은 [도메인 문서](../Home.md#-도메인) 참조. SSE 엔드포인트는 `text/event-stream` 으로 표시.

## Auth · Member — `/api/auth`, `/api/members`, `/api/admin/members`

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/auth/signup` | 회원가입 |
| POST | `/api/auth/login` | 로그인 (JWT 발급) |
| POST | `/api/auth/refresh` | 토큰 갱신 |
| POST | `/api/auth/logout` | 로그아웃 |
| GET / PUT | `/api/members/me` | 내 정보 조회/수정 |
| PUT | `/api/members/me/password` | 비밀번호 변경 |
| GET / POST / PUT / DELETE | `/api/admin/members[/{id}]` | 관리자 회원 CRUD |
| PATCH | `/api/admin/members/{id}/reset-password` | 비번 초기화 |
| GET | `/api/admin/members/owners` | 점주 목록 |

## Store — `/api/stores`, `/api/admin/stores`

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/stores` | 매장 목록 (고객) |
| GET | `/api/stores/{storeId}` | 매장 상세 |
| GET | `/api/stores/{storeId}/menus` | 매장 메뉴 |
| GET / POST / PUT | `/api/admin/stores[/{id}]` | 관리자 매장 CRUD |
| GET | `/api/admin/stores/my-stores` | 내 매장 목록 (점주) |
| PATCH | `/api/admin/stores/{id}/open\|close` | 영업 상태 변경 |

## Menu — `/api/admin/menus`

| Method | Path | 설명 |
|--------|------|------|
| GET / POST / PUT / DELETE | `/api/admin/menus[/{id}]` | 메뉴 CRUD (multipart) |
| PATCH | `/api/admin/menus/{id}/activate` | 활성/비활성 토글 |
| POST / PUT / DELETE | `/api/admin/menus/{id}/options[/{optId}]` | 옵션 관리 |

## Stock — `/api/stores/{storeId}/stocks`, `/api/admin/stores/{storeId}/stocks`

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/stores/{storeId}/stocks` | 매장 재고 (고객) |
| GET / PUT | `/api/admin/stores/{storeId}/stocks[/{menuId}]` | 재고 조회/수정 |
| GET | `/api/admin/stores/{storeId}/stocks/restore-failures` | 복구 실패 목록 |
| GET | `/api/admin/stock-restore-failures` | 전체 복구 실패 목록 |

## Cart — `/api/cart`

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/cart` | 장바구니 조회 |
| POST | `/api/cart/items` | 항목 추가 |
| PATCH | `/api/cart/items/{itemKey}` | 항목 수정 |
| DELETE | `/api/cart/items/{itemKey}` | 항목 삭제 |
| DELETE | `/api/cart` | 전체 비우기 |

## Order — `/api/orders`, `/api/admin/orders`

> 주문 **생성**은 결제와 묶여 있어 `POST /api/payments` 에서 처리한다. 이 도메인은 조회/취소만 노출.

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/orders` | 내 주문 목록 |
| GET / DELETE | `/api/orders/{orderId}` | 상세 / 취소 |
| GET (SSE) | `/api/orders/{orderId}/result` | 초기 처리 결과 푸시 |
| GET (SSE) | `/api/orders/{orderId}/updates` | 상태 전이 푸시 |
| GET | `/api/orders/{orderId}/queue-position` | 대기열 순번 |
| GET | `/api/admin/orders` | 관리자 주문 목록 |
| GET (SSE) | `/api/admin/orders/stream` | 어드민 실시간 스트림 |
| PATCH | `/api/admin/orders/{orderId}/{action}` | 상태 전이 (accept/prepare/ready/complete/cancel) |

## Payment — `/api/payments`

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/payments` | 결제 시도 |
| GET | `/api/payments/orders/{orderId}` | 주문별 결제 정보 |

## Event — `/api/events`, `/api/admin`

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/events[/{eventId}]` | 이벤트 목록/상세 |
| GET | `/api/events/{eventId}/queue/status` | 대기열 상태 |
| POST | `/api/events/{eventId}/queue/join` | 대기열 진입 |
| GET (SSE) | `/api/events/{eventId}/queue/sse` | 본인 순번 푸시 |
| POST | `/api/events/{eventId}/purchase` | 구매 확정 |
| POST / GET | `/api/admin/goods` | 굿즈 CRUD |
| POST / GET | `/api/admin/events` | 이벤트 CRUD |
| PATCH | `/api/admin/events/{id}/open\|close` | 이벤트 시작/종료 |

## Demo — `/demo/stock`

| Method | Path | 설명 |
|--------|------|------|
| GET (SSE) | `/demo/stock/stream` | 동시성 데모 스트림 |
| GET (SSE) | `/demo/stock/queue-stream` | 큐 상태 데모 |

---

**원본 컨트롤러**: `src/main/java/com/whale/order/domain/*/controller/`
