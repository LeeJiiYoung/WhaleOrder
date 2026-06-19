# Store — 매장

> 매장 관리 + 영업시간 기반 상태 자동 전이 스케줄러.

**디렉토리**: `src/main/java/com/whale/order/domain/store/`

## 구성

| 분류 | 파일 |
|------|------|
| Entity | `Store`, `StoreStatus` |
| Controller | `AdminStoreController`, `CustomerStoreController` |
| Service | `StoreService` |
| Scheduler | `StoreStatusScheduler` |
| Repository | `StoreRepository` |

## 핵심 플로우

- **어드민**: 매장 CRUD, 영업시간/위치 설정
- **고객**: 매장 목록/상세 조회
- **자동 상태 전이**: `StoreStatusScheduler` 가 영업시간 기준으로 `StoreStatus` (OPEN/CLOSED 등) 갱신

## 의존 관계

- `Menu` 는 `Store` 에 속함 (1:N)
- `Stock` 은 `Store` × `Menu` 조합으로 관리
- `Order` 는 `Store` 단위로 생성
