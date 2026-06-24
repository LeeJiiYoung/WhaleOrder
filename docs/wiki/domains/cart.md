# Cart — 장바구니

> 사용자 장바구니. RDB가 아닌 Redis Hash에 저장 (24h TTL).

**디렉토리**: `src/main/java/com/whale/order/domain/cart/`

## 구성

| 분류         | 파일                                           |
| ---------- | -------------------------------------------- |
| Controller | `CartController`                             |
| Service    | `CartService`                                |
| DTO        | `CartAddRequest`, `CartItem`, `CartResponse` |

## 저장 구조

- 키: `cart:{memberId}`
- 자료구조: Redis `Hash` (field = itemKey, value = CartItem JSON)
- itemKey 형식: `{menuId}:{optionId1,optionId2,...}` — 같은 메뉴 다른 옵션 조합을 별도 항목으로 구분
- TTL: 24시간

## DTO 필드

- `CartAddRequest`: `storeId`, `menuId`, `quantity`, `selectedOptions[]` — **`storeId` 는 재고 검증에 사용**
- `CartItem`: `itemKey`, `storeId`, `menuId`, `menuName`, `imageUrl`, `basePrice`, `quantity`, `selectedOptions[]`, `unitPrice`, `totalPrice` — **`storeId` 를 저장해두면 `updateQuantity` 시에도 같은 매장 기준으로 재고 재검증 가능**

## 검증 정책

| 시점 | 검증 항목 |
|---|---|
| `addItem` | (1) 메뉴 존재 (2) **필수 옵션 그룹 모두 선택** (3) **단일 매장 정책** (4) **Stock 존재 + (quantity >= 요청수량) 충족** |
| `updateQuantity (+)` | Stock 수량 ≥ 새 수량 — 증가 방향만 검증 |
| `updateQuantity (-)` | 검증 없음 — 감소는 항상 허용 |
| `updateQuantity (=0)` | 항목 삭제로 위임 |

### 단일 매장 정책

카트 키가 `cart:{memberId}` 한 가지로 매장별 분리가 없으므로, 한 카트는 동시에 한 매장의 메뉴만 담을 수 있다.

- 카트에 이미 다른 매장의 메뉴가 있는 상태에서 새 매장 메뉴 담기 시도 → `DifferentStoreCartException` → **HTTP 412 Precondition Failed**
- 응답 메시지: `"장바구니에 이미 다른 매장의 메뉴가 담겨있습니다. 담으면 이전 매장의 메뉴는 삭제됩니다."`
- 프런트는 412 응답을 받으면 `window.confirm` 으로 사용자 동의를 받고 `POST /api/cart/items?force=true` 로 재요청 → 백엔드는 `clearCart` 후 새 매장 메뉴를 담는다
- 구버전 `CartItem` 에 `storeId` 가 null 인 항목(이전 배포 데이터)은 매장 비교에서 제외

- **`quantity = -1`** (무제한 재고) 은 모든 검증 통과
- 같은 메뉴+옵션 조합이 이미 카트에 있으면 **합산 수량** 으로 검증 (예: 카트 3 + 추가 5 = 8 vs 재고 6 → 차단)
- 품절(quantity=0) 메뉴는 담기 단계에서 차단되어 결제까지 가지 않음 → UX 개선 + 결제 후 자동 환불 트래픽 절감
- 구버전 `CartItem` 에 `storeId` 가 없으면 (24h TTL 만료 전 이전 배포 데이터) 재고 검증 스킵

## 이유

- 수명이 짧고, 사용자별 단순 CRUD가 많아 RDB 부담 큼
- 주문 완료 시 비워지므로 영속성 불필요
- 주문 트랜잭션 커밋 후(`AFTER_COMMIT`) 장바구니 정리 → 결제 실패 시에도 장바구니 보존

## 관련 문서

- [Redis 활용처 — 장바구니](../architecture/redis-usage.md#1-장바구니-hash)
- [Kafka — AFTER_COMMIT 발행](../architecture/kafka-event-stream.md#발행-시점--after_commit)
