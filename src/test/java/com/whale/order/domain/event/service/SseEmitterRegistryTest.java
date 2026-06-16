package com.whale.order.domain.event.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SseEmitterRegistry} 단위 테스트.
 *
 * <p>대기열 순번 푸시에 사용하는 SSE 연결 저장소를 검증한다.
 * {@code ConcurrentHashMap} 기반의 순수 Java 클래스이므로
 * Spring 컨텍스트 없이 실행된다.
 *
 * <p>검증 항목
 * <ul>
 *   <li>register / get / has — 등록 후 조회</li>
 *   <li>remove — 수동 제거 후 조회 불가</li>
 *   <li>재등록 — 동일 memberId 재등록 시 새 emitter로 덮어쓰기</li>
 * </ul>
 *
 * <p>참고: {@code onCompletion} 콜백은 실제 HTTP 핸들러가 연결되어야 동작하므로
 * 단위 테스트 환경에서는 검증하지 않는다.
 */
class SseEmitterRegistryTest {

    private SseEmitterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SseEmitterRegistry();
    }

    @Test
    @DisplayName("register 후 get으로 조회된다")
    void register_후_get_조회() {
        SseEmitter emitter = new SseEmitter();

        registry.register(1L, emitter);

        assertThat(registry.get(1L)).isEqualTo(emitter);
        assertThat(registry.has(1L)).isTrue();
    }

    @Test
    @DisplayName("remove 후 조회되지 않는다")
    void remove_후_조회_안됨() {
        registry.register(1L, new SseEmitter());

        registry.remove(1L);

        assertThat(registry.get(1L)).isNull();
        assertThat(registry.has(1L)).isFalse();
    }

    @Test
    @DisplayName("등록하지 않은 memberId는 has가 false이다")
    void 미등록_memberId_has_false() {
        assertThat(registry.has(999L)).isFalse();
        assertThat(registry.get(999L)).isNull();
    }

    @Test
    @DisplayName("동일 memberId로 재등록하면 새 emitter로 덮어쓴다")
    void 재등록_시_새_emitter로_교체() {
        SseEmitter first  = new SseEmitter();
        SseEmitter second = new SseEmitter();

        registry.register(1L, first);
        registry.register(1L, second);

        assertThat(registry.get(1L)).isEqualTo(second);
    }
}
