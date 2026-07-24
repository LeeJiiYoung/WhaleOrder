# SSE 재연결 복구 계획

> **상태: 미도입 (계획 문서)** — 현재 코드에 재연결 버퍼/Last-Event-ID 처리 없음. 이 문서는 실서비스 이관 시 어디에 어떻게 붙일지 정리한 설계 초안.

## 왜 필요한가

현재 SSE 구현은 **"연결이 끊기지 않는다"는 가정 하에 동작**한다. 연결이 끊긴 동안 발생한 이벤트는 버퍼 없이 드롭된다.

끊김이 발생하는 현실적인 원인:
- 모바일 네트워크 전환 (LTE → WiFi)
- 로드밸런서/프록시의 유휴 연결 타임아웃 (하트비트가 막아주지만 100% 보장 아님)
- 서버 롤링 배포 시 기존 연결 종료
- 브라우저 탭 백그라운드 전환 후 OS가 소켓 회수

## 채널별 현재 동작과 문제

### result 채널 — 부분적으로 안전, 재연결엔 취약

`pendingResults` Map이 있어서 **워커 선처리 → 브라우저 나중 연결** 레이스는 커버한다.

```java
// notify(): 브라우저 없으면 보관
SseEmitter emitter = emitters.remove(orderId);
if (emitter == null) {
    pendingResults.put(orderId, json);  // 임시 보관
    return;
}

// register(): 연결 시 꺼내서 즉시 전송
String stored = pendingResults.remove(orderId);
if (stored != null) {
    emitter.send(...);
    emitter.complete();
}
```

**하지만 재연결 시나리오엔 동작하지 않는다.** 최초 연결 시 `emitters.remove(orderId)`가 실행된 이후 연결이 끊기면, 재연결 시 `pendingResults`에 아무것도 없다 → **유실**.

### status 채널 — 유실됨

```java
public void notifyStatusUpdate(Long orderId, String status, String message) {
    SseEmitter emitter = statusEmitters.get(orderId);
    if (emitter == null) return;  // 연결 없으면 그냥 드롭
    ...
}
```

어드민이 "제조 중"으로 바꾸는 순간 클라이언트가 끊겨 있으면, 그 이벤트는 사라진다. 재연결해도 마지막 상태를 알 수 없다.

### admin 브로드캐스트 채널 — 유실됨

```java
adminEmitters.forEach((clientId, emitter) -> send(emitter, "newOrder", json));
```

`adminEmitters`에 없는 클라이언트엔 전송 자체를 시도하지 않는다.

## 리소스 정리는 현재도 올바르게 동작

참고로, **리소스 정리(emitter 제거) 자체는 문제없다.** 두 경로가 모두 커버한다:

- **정상 경로**: 등록 시 건 콜백 (`onCompletion`, `onTimeout`, `onError`)이 Map에서 자동 제거
- **조용한 끊김 경로**: 하트비트 전송 실패 시 `IOException` catch → `adminEmitters.remove(clientId)` 명시적 제거

```java
@Scheduled(fixedDelay = 25_000)
public void sendAdminHeartbeat() {
    adminEmitters.forEach((clientId, emitter) -> {
        try {
            emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
        } catch (IOException e) {
            adminEmitters.remove(clientId);  // 여기서 정리
        }
    });
}
```

리소스 누수는 없지만, 정리된 후 재연결 시 메시지 복구 수단이 없는 것이 문제.

## 해결 방향 두 가지

### 방향 A — SSE 스펙 Last-Event-ID 활용 (이벤트 재전송)

SSE 프로토콜 자체에 재연결 복구 메커니즘이 있다. 브라우저는 연결이 끊기면 자동으로 재연결을 시도하면서 `Last-Event-ID` 헤더를 보낸다.

**서버 측 변경 사항**

이벤트 전송 시 `id` 필드 추가:
```java
emitter.send(SseEmitter.event()
    .id(String.valueOf(eventId))    // ← 현재 없음, 추가 필요
    .name("status")
    .data(json));
```

재연결 시 누락 이벤트 재전송:
```java
public SseEmitter registerStatusStream(Long orderId, String lastEventId) {
    SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
    statusEmitters.put(orderId, emitter);
    // ...콜백 등록...

    // 재연결 시 누락 이벤트 즉시 전송
    if (lastEventId != null) {
        replayMissedEvents(orderId, Long.parseLong(lastEventId), emitter);
    }
    return emitter;
}
```

**이벤트 버퍼 저장소 필요**

이벤트를 재전송하려면 어딘가에 보관해야 한다:

```sql
-- 옵션 1: PostgreSQL 이벤트 로그
CREATE TABLE sse_event_log (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    event_name VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sse_event_log ON sse_event_log(order_id, id);
```

```
-- 옵션 2: Redis Sorted Set (TTL 설정 용이)
ZADD sse:events:{orderId} {eventId} {payload}
EXPIRE sse:events:{orderId} 1800   -- 30분
```

**재전송 흐름**
```
클라이언트 재연결 요청 (Last-Event-ID: 42)
  → registerStatusStream(orderId, lastEventId="42")
  → SELECT * FROM sse_event_log WHERE order_id=? AND id > 42
  → 누락 이벤트들 순서대로 emitter.send()
  → 이후 실시간 스트림 재개
```

**장점**: 클라이언트 코드 변경 없음. 브라우저 EventSource가 자동으로 Last-Event-ID를 붙여 재연결.  
**단점**: 이벤트 저장소 추가. 버퍼 TTL 관리 필요.

---

### 방향 B — 재연결 시 DB 스냅샷 조회 (현재 구조에 최소 변경)

이벤트 버퍼 없이, 재연결 시 클라이언트가 REST API로 현재 상태를 명시적으로 조회하고 SSE는 그 이후의 delta만 받는 방식.

**흐름**
```
1. 클라이언트: SSE 연결 끊김 감지
2. 클라이언트: GET /api/orders/{orderId} → 현재 주문 상태 조회 (DB 기반, 항상 최신)
3. 클라이언트: UI 상태 갱신
4. 클라이언트: SSE 재연결 → 이후 상태 변화만 스트리밍
```

**서버 측 변경 최소화**: 별도 엔드포인트나 버퍼 불필요. 현재 주문 조회 API가 이미 존재.

**클라이언트 측 처리**
```js
const es = new EventSource(`/api/orders/${orderId}/updates`);

es.onerror = async () => {
    // 연결 끊기면 현재 상태 폴링으로 복구
    const res = await fetch(`/api/orders/${orderId}`);
    const order = await res.json();
    updateUI(order.status);
    // EventSource는 자동 재연결 시도
};
```

**장점**: 서버 변경 거의 없음. 구현 단순. 상태 자체는 DB에서 항상 정확하게 조회 가능.  
**단점**: 재연결 순간 이벤트 1개를 놓칠 수 있음 (조회 응답 vs 상태 변경 타이밍). 클라이언트 로직 추가 필요.

## 도입 우선순위

| 순위 | 대상 | 방향 | 이유 |
|-----|------|------|------|
| 1 | **status 채널** (고객용 주문 상태) | B (스냅샷 조회) | 고객 UX 직결. "제조 중인데 화면은 접수됨" 은 CS 유발 |
| 2 | admin 브로드캐스트 채널 | B (스냅샷 조회) | 어드민 재연결 시 주문 목록 재조회로 커버 가능 |
| 3 | result 채널 재연결 | A (Last-Event-ID) | 재고 차감 결과는 1회성이라 놓치면 복구가 애매함 |
| 4 | (선택) 전체 채널 Last-Event-ID | A | 완전한 재연결 복구. 인프라 복잡도 감수할 수 있을 때 |

**1차 도입은 B (스냅샷 조회)**, 트래픽 커지고 이벤트 유실이 실제 문제가 되면 A로 전환.

## 도입 시 함께 고민할 것

- **스케일아웃 시 emitter Map 문제**: 현재 인메모리 `ConcurrentHashMap` 은 단일 인스턴스 한정. 인스턴스가 여러 개면 클라이언트가 재연결 시 다른 인스턴스에 붙을 수 있어서 Map에 emitter가 없음 → Redis Pub/Sub 또는 STOMP 도입 필요 (이미 wiki에 한계로 명시됨)
- **Last-Event-ID 버퍼 TTL**: 주문이 완료/취소된 뒤 이벤트 로그를 언제 지울지. 주문 종료 후 30분 정도가 적절
- **재연결 폭풍(thundering herd)**: 서버 재배포 시 수천 개 SSE 연결이 동시에 재연결을 시도. 클라이언트 측 지수 백오프 필수
- **EventSource 자동 재연결 간격**: 브라우저 기본값은 3초. 서버가 `retry: 5000` 필드로 제어 가능

## 관련 대화

- 2026-07-24: Q13 인터뷰 준비 중 하트비트 감지 이후 리소스 정리와 메시지 유실 문제 분석. 채널별로 유실 여부가 다름을 확인. 단기는 스냅샷 방향 B, 장기는 Last-Event-ID 방향 A로 정리.

## 참고

- [실시간 푸시 — SSE](realtime-sse.md) — 현재 구현 상세 및 채널 구성
