# Redis 활용처

> WhaleOrder 내 Redis는 단순 캐시 이상으로 4가지 역할을 담당

**관련 코드**
- `src/main/java/com/whale/order/domain/cart/service/CartService.java`
- `src/main/java/com/whale/order/global/auth/RefreshTokenService.java`
- `src/main/java/com/whale/order/domain/order/service/OrderQueueService.java`
- `src/main/java/com/whale/order/domain/menu/service/MenuService.java`
- `src/main/java/com/whale/order/domain/stock/service/StockLockFacade.java`
- `src/main/java/com/whale/order/global/config/RedissonConfig.java`

## 4가지 역할

### 1. 장바구니 (Hash)

- 자료구조: `Hash`
- 키: `cart:{memberId}`
- TTL: 24시간
- 이유: 수명이 짧고 사용자별 단순 CRUD가 많음. RDB에 두면 부하 큼.

### 2. 리프레시 토큰 (String)

- 자료구조: `String`
- 키: `refresh:{memberId}`
- TTL: 토큰 만료 시간과 동기화
- 이유: 빠른 무효화 + TTL 자동 만료 활용

### 3. 주문 대기열 (ZSet)

- 자료구조: `Sorted Set`
- 키: `order:queue`
- 점수: 주문 생성 timestamp
- 이유: 시간순 처리 + 백그라운드 워커가 polling 처리

### 4. 메뉴 캐시 (@Cacheable)

- 캐시명: `menus`, `menu`
- 무효화: 메뉴 변경 시 `@CacheEvict`
- 이유: 메뉴 조회는 압도적으로 읽기 우위

### 5. 분산 락 (Redisson RLock)

- 자료구조: Redisson 내부 락 객체
- 키: `stock:lock:{storeId}:{menuId}`
- 자세한 내용: [동시성 제어](concurrency-control.md)

## 키 네임스페이스 컨벤션

| 패턴 | 예시 |
|------|------|
| `cart:{memberId}` | `cart:1001` |
| `refresh:{memberId}` | `refresh:1001` |
| `stock:lock:{storeId}:{menuId}` | `stock:lock:5:42` |
| `order:queue` | (단일 키, 전역 ZSet) |

## 한계와 다음 단계

- 단일 Redis 노드 — 운영 시 Sentinel 또는 Cluster 모드 검토
- 세션 데이터는 현재 사용 안 함 (JWT 기반) — 추후 서버사이드 세션 도입 시 키 네임스페이스 추가 필요
