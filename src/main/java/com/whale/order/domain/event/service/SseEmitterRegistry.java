package com.whale.order.domain.event.service;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 대기열에서 순번을 받고 있는 SSE 연결을 보관한다.
 * Scheduler가 순번을 푸시할 때 여기서 emitter를 꺼내 사용한다.
 *
 * key : memberId
 */
@Component
public class SseEmitterRegistry {

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public void register(Long memberId, SseEmitter emitter) {
        emitters.put(memberId, emitter);
        // 연결 종료 시 자동 제거
        emitter.onCompletion(() -> emitters.remove(memberId));
        emitter.onTimeout(() -> emitters.remove(memberId));
        emitter.onError(e -> emitters.remove(memberId));
    }

    public SseEmitter get(Long memberId) {
        return emitters.get(memberId);
    }

    public void remove(Long memberId) {
        emitters.remove(memberId);
    }

    public boolean has(Long memberId) {
        return emitters.containsKey(memberId);
    }
}
