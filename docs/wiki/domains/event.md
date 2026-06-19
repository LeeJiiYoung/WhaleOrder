# Event — 이벤트/프로모션

> 한정 수량 굿즈 판매 같은 선착순 이벤트. Redis ZSet 기반 대기열로 트래픽 제어.

**디렉토리**: `src/main/java/com/whale/order/domain/event/`

## 구성

| 분류 | 파일 |
|------|------|
| Entity | `Event`, `EventPurchase`, `EventStatus`, `Goods` |
| Controller | `AdminEventController`, `EventQueueController` |
| Service | `EventService`, `EventQueueService`, `EventQueueFacade`, `EventPurchaseService`, `EventScheduler` |
| SSE | `SseEmitterRegistry` (이벤트 도메인 자체 SSE 등록소) |
| Repository | `EventRepository`, `EventPurchaseRepository`, `GoodsRepository` |

## 핵심 플로우

```
이벤트 시작 → 사용자 대기열 진입 (ZSet)
            → 워커가 순서대로 EventPurchase 처리
            → SseEmitterRegistry로 본인 순번/완료 알림
```

- 대기열: Redis `Sorted Set`
- 순서: 진입 timestamp 기준
- 스케줄러: `EventScheduler` 가 시작/종료 자동 전이

## 관련 문서

- [Redis 활용처 — 대기열](../architecture/redis-usage.md#3-주문-대기열-zset)
