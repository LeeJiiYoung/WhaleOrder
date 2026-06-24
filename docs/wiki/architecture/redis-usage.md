# Redis 활용처

> WhaleOrder 내 Redis는 단순 캐시 이상으로 6가지 역할을 담당

**관련 코드**
- `src/main/java/com/whale/order/domain/cart/service/CartService.java`
- `src/main/java/com/whale/order/global/auth/RefreshTokenService.java`
- `src/main/java/com/whale/order/domain/order/service/OrderQueueService.java`
- `src/main/java/com/whale/order/domain/menu/service/MenuService.java`
- `src/main/java/com/whale/order/domain/stock/service/StockLockFacade.java`
- `src/main/java/com/whale/order/global/idempotency/IdempotencyService.java`
- `src/main/java/com/whale/order/global/config/RedissonConfig.java`

## 6가지 역할

### 1. 장바구니 (Hash)

- 자료구조: `Hash`
- 키: `cart:{memberId}` — **매장별 분리 X. 한 번에 한 매장만 담는 단일 매장 정책**
  (자세한 내용은 [Cart 도메인](../domains/cart.md) 의 "단일 매장 정책" 섹션 참조)
- TTL: 24시간
- 이유: 수명이 짧고 사용자별 단순 CRUD가 많음. RDB에 두면 부하 큼.
- 코드: `src/main/java/com/whale/order/domain/cart/service/CartService.java`

### 2. 리프레시 토큰 (String)

- 자료구조: `String`
- 키: `refresh:{memberId}`
- TTL: 토큰 만료 시간과 동기화
- 이유: 빠른 무효화 + TTL 자동 만료 활용
- 코드: `src/main/java/com/whale/order/global/auth/RefreshTokenService.java`

### 3. 주문 대기열 (ZSet)

- 자료구조: `Sorted Set`
- 키: `order:queue`
- 점수: 주문 생성 timestamp
- 이유: 시간순 처리 + 백그라운드 워커가 polling 처리
- 코드: `src/main/java/com/whale/order/domain/order/service/OrderQueueService.java` · `OrderQueueWorker.java`

### 4. 메뉴 캐시 (@Cacheable)

- 캐시명: `menus`, `menu`
- 무효화: 메뉴 변경 시 `@CacheEvict`
- 이유: 메뉴 조회는 압도적으로 읽기 우위
- 코드: `src/main/java/com/whale/order/domain/menu/service/MenuService.java`

### 5. 분산 락 (Redisson RLock)

- 자료구조: Redisson 내부 락 객체
- 키: `stock:lock:{storeId}:{menuId}`
- 자세한 내용: [동시성 제어](concurrency-control.md)
- 코드: `src/main/java/com/whale/order/domain/stock/service/StockLockFacade.java` · `src/main/java/com/whale/order/global/config/RedissonConfig.java`

### 6. 멱등성 키 (String, SET NX EX)

- 자료구조: `String` (RBucket)
- 키: `idem:{SHA-256(memberId·storeId·method·orderType·customerRequest·cart)}`
- TTL: PROCESSING 60초 / COMPLETED 60초
- 이유: `SET NX EX` 한 줄로 원자성 + TTL 자동 만료 → 죽은 PROCESSING 키가 무한 점유 불가. 이전 PostgreSQL `ON CONFLICT + delete/insert` 구조의 race 위험 제거.
- 동작 요약:
  - `markProcessing(key)` → `RBucket.setIfAbsent("__PROCESSING__", 60s)` — 단일 호출자만 true
  - `saveResult(key, result)` → JSON 직렬화 후 60초 TTL 로 set
  - `getResult(key, type)` → PROCESSING 마커면 null, JSON 이면 역직렬화 반환
  - `delete(key)` → 실패 시 호출자 재시도 허용
- 코드: `src/main/java/com/whale/order/global/idempotency/IdempotencyService.java`

## 키 네임스페이스 컨벤션

| 패턴 | 예시 |
|------|------|
| `cart:{memberId}` | `cart:1001` |
| `refresh:{memberId}` | `refresh:1001` |
| `stock:lock:{storeId}:{menuId}` | `stock:lock:5:42` |
| `order:queue` | (단일 키, 전역 ZSet) |
| `idem:{sha256}` | `idem:a3f5b8...` |

## 한계와 다음 단계

- 단일 Redis 노드 — 운영 시 Sentinel 또는 Cluster 모드 검토
- 세션 데이터는 현재 사용 안 함 (JWT 기반) — 추후 서버사이드 세션 도입 시 키 네임스페이스 추가 필요
