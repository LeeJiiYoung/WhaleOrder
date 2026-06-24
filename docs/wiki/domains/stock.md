# Stock — 재고

> 매장×메뉴 단위 재고 관리. 분산 락으로 동시성 제어, 복구 실패는 별도 테이블에 기록.

**디렉토리**: `src/main/java/com/whale/order/domain/stock/`

## 구성

| 분류 | 파일 |
|------|------|
| Entity | `Stock`, `StockRestoreFailure` |
| Controller | `AdminStockController`, `CustomerStockController`, `AdminStockRestoreFailureController`, `StockConcurrencyDemoController` |
| Service | `StockService`, `StockLockFacade`, `StockDemoService` |
| Repository | `StockRepository`, `StockRestoreFailureRepository` |

## 책임 분리

| 클래스 | 책임 |
|--------|------|
| `StockService` | 실제 DB 차감/복구 로직 (락 없음) |
| `StockLockFacade` | Redisson 분산 락으로 직렬화 후 `StockService` 호출 |
| `StockRestoreFailure` | 복구 실패 시 수동 보정용 기록 테이블 |
| `StockDemoService` / `StockConcurrencyDemoController` | 동시성 데모용 |

## 복구 실패 모니터링

- 저장: `StockRestoreFailureRepository`
- 어드민 SSE 알림: `OrderSseService.broadcastStockRestoreFailure()`
- 조회: `AdminStockRestoreFailureController`

## 관련 문서

- [동시성 제어 — 분산 락](../architecture/concurrency-control.md)
- [Saga 보상 — 재고 복구 재시도](../architecture/saga-compensation.md#3-재고-복구-재시도-200ms--3회)
