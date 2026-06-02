package com.whale.order.domain.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSseService {

    /** orderId → SSE 연결. 브라우저가 /result 구독 중일 때 여기에 등록됨 */
    private final ConcurrentHashMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 워커(200ms 주기 백그라운드 처리기)가 재고 차감을 완료했는데
     * 브라우저가 아직 SSE 연결 전일 때 결과를 임시 보관.
     * 브라우저가 나중에 연결하면 여기서 꺼내 즉시 전송한다.
     */
    private final ConcurrentHashMap<Long, String> pendingResults = new ConcurrentHashMap<>();

    /** orderId → SSE 연결. 어드민 상태 변경(수락/제조/완료) 알림용 */
    private final ConcurrentHashMap<Long, SseEmitter> statusEmitters = new ConcurrentHashMap<>();

    /** clientId(UUID) → SSE 연결. 새 주문 발생 시 어드민 브라우저 전체에 브로드캐스트 */
    private final ConcurrentHashMap<String, SseEmitter> adminEmitters = new ConcurrentHashMap<>();

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED");

    private final ObjectMapper objectMapper;

    // ── 초기 처리 결과 (재고 차감 결과) ─────────────────────────────

    /**
     * 브라우저가 /result SSE 엔드포인트에 연결할 때 호출.
     * 워커가 이미 처리를 끝낸 경우 pendingResults에 결과가 있으므로 즉시 전송하고 종료.
     * 아직 처리 중이면 emitters에 등록해두고 워커가 notify()를 호출할 때까지 대기.
     */
    public SseEmitter register(Long orderId) {
        String stored = pendingResults.remove(orderId);
        if (stored != null) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("result").data(stored));
                emitter.complete();
            } catch (IOException ignored) {}
            return emitter;
        }

        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        emitters.put(orderId, emitter);
        emitter.onCompletion(() -> emitters.remove(orderId));
        emitter.onTimeout(() -> emitters.remove(orderId));
        return emitter;
    }

    /**
     * 워커가 재고 차감 완료 후 호출.
     * 브라우저가 연결 중이면 즉시 전송, 연결 전이면 pendingResults에 보관.
     */
    public void notify(Long orderId, Map<String, Object> data) {
        String json = toJson(data);
        if (json == null) return;

        SseEmitter emitter = emitters.remove(orderId);
        if (emitter == null) {
            pendingResults.put(orderId, json);
            return;
        }
        send(emitter, "result", json);
    }

    // ── 상태 변경 알림 (어드민 액션) ─────────────────────────────────

    /**
     * 브라우저가 /updates SSE 엔드포인트에 연결할 때 호출.
     * 어드민이 주문 수락/제조/완료 처리 시 notifyStatusUpdate()로 이벤트를 받는다.
     */
    public SseEmitter registerStatusStream(Long orderId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30분
        statusEmitters.put(orderId, emitter);
        emitter.onCompletion(() -> statusEmitters.remove(orderId));
        emitter.onTimeout(() -> statusEmitters.remove(orderId));
        return emitter;
    }

    /**
     * 어드민이 주문 상태를 변경할 때 호출. 연결된 브라우저에 상태와 메시지를 전송.
     * COMPLETED/CANCELLED 같은 종료 상태면 이벤트 전송 후 SSE 연결을 닫는다.
     */
    public void notifyStatusUpdate(Long orderId, String status, String message) {
        SseEmitter emitter = statusEmitters.get(orderId);
        if (emitter == null) return;

        Map<String, Object> data = Map.of("status", status, "message", message);
        String json = toJson(data);
        if (json == null) return;

        boolean terminal = TERMINAL.contains(status);
        if (terminal) statusEmitters.remove(orderId);

        if (terminal) {
            send(emitter, "status", json);
            try { emitter.complete(); } catch (Exception ignored) {}
        } else {
            send(emitter, "status", json);
        }
    }

    // ── 어드민 새 주문 브로드캐스트 ──────────────────────────────────

    /** 어드민 브라우저가 /stream 엔드포인트에 연결할 때 호출 */
    public SseEmitter registerAdmin(String clientId) {
        SseEmitter emitter = new SseEmitter(0L); // heartbeat로 연결 유지, 서버 측 타임아웃 없음
        adminEmitters.put(clientId, emitter);
        emitter.onCompletion(() -> adminEmitters.remove(clientId));
        emitter.onTimeout(()    -> adminEmitters.remove(clientId));
        emitter.onError((e)     -> adminEmitters.remove(clientId));
        return emitter;
    }

    /** 새 주문 접수 시 연결된 모든 어드민 브라우저에 전송 */
    public void broadcastNewOrder(Object orderData) {
        String json = toJson(orderData);
        if (json == null) return;
        adminEmitters.forEach((clientId, emitter) -> send(emitter, "newOrder", json));
    }

    /** 재고 복구 실패 시 연결된 모든 어드민 브라우저에 경고 전송 */
    public void broadcastStockRestoreFailure(Long orderId, Long menuId, int quantity) {
        Map<String, Object> data = Map.of(
                "orderId", orderId,
                "menuId", menuId,
                "quantity", quantity,
                "message", "재고 복구 실패 — 수동 보정 필요"
        );
        String json = toJson(data);
        if (json == null) return;
        adminEmitters.forEach((clientId, emitter) -> send(emitter, "stockRestoreFailure", json));
    }

    /** 25초마다 heartbeat 전송 — 브라우저/프록시의 유휴 연결 끊김 방지 */
    @Scheduled(fixedDelay = 25_000)
    public void sendAdminHeartbeat() {
        adminEmitters.forEach((clientId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
            } catch (IOException e) {
                adminEmitters.remove(clientId);
            }
        });
    }

    // ── 공통 ────────────────────────────────────────────────────────

    private void send(SseEmitter emitter, String eventName, String json) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (IOException e) {
            log.warn("SSE 전송 실패 event={}", eventName);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("SSE 직렬화 실패", e);
            return null;
        }
    }
}
