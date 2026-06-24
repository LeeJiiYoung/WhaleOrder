# 실시간 푸시 — SSE (Server-Sent Events)

> 주문 접수 → 제조 중 → 완료 상태를 클라이언트 브라우저에 단방향 푸시. 어드민에는 새 주문/재고복구 실패도 브로드캐스트.

**관련 코드**
- `src/main/java/com/whale/order/domain/order/service/OrderSseService.java`
- `src/main/java/com/whale/order/domain/event/service/SseEmitterRegistry.java`

## 채널 구성

| 채널                                                        | 용도                | 등록 메서드                          | 알림 메서드                                         |
| --------------------------------------------------------- | ----------------- | ------------------------------- | ---------------------------------------------- |
| `result`                                                  | 워커의 재고 차감 결과 (고객) | `register(orderId)`             | `notify(orderId, data)`                        |
| `status`                                                  | 어드민의 상태 전이 (고객)   | `registerStatusStream(orderId)` | `notifyStatusUpdate(orderId, status, message)` |
| `newOrder` / `orderStatusChanged` / `stockRestoreFailure` | 어드민 브로드캐스트        | `registerAdmin(clientId)`       | `broadcastNewOrder()` 등                        |

## 해결한 문제

**1. Race condition — 워커가 먼저, 브라우저가 나중에 연결**

워커(200ms 주기)가 처리를 끝낸 뒤 브라우저가 연결하는 경우 결과 유실 위험. `pendingResults` Map에 결과를 보관해두고, 브라우저가 `register()` 호출 시 즉시 꺼내서 전송.

```java
String stored = pendingResults.remove(orderId);
if (stored != null) {
    emitter.send(SseEmitter.event().name("result").data(stored));
    emitter.complete();
}
```

**2. 어드민 다중 클라이언트 브로드캐스트**

`adminEmitters` (clientId → emitter) 에 모든 어드민 브라우저를 등록하고 `forEach`로 동시 전송.

**3. 유휴 연결 끊김**

`@Scheduled(fixedDelay = 25_000)` 로 25초마다 `heartbeat` 이벤트 전송 → 프록시/로드밸런서가 유휴 연결 끊는 것 방지.

**4. 종료 상태 처리**

`COMPLETED` / `CANCELLED` 같은 종료 상태는 이벤트 전송 후 `emitter.complete()` 로 연결 닫음. 메모리 누수 방지.

## 클라이언트 측 (참고)

```js
const es = new EventSource(`/api/orders/${orderId}/updates`);
es.addEventListener("status", (e) => {
    const { status, message } = JSON.parse(e.data);
    // UI 업데이트
});
```

## 한계와 다음 단계

- 인스턴스 스케일아웃 시 인메모리 `ConcurrentHashMap` 으로는 부족 → Redis Pub/Sub 또는 STOMP/WebSocket 도입 검토
- `result` 채널의 `pendingResults` 는 TTL 없음 → 메모리 누수 방어용 정리 스케줄러 추가 필요
