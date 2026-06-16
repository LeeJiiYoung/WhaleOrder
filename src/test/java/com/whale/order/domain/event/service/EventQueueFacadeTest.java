package com.whale.order.domain.event.service;

import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EventQueueFacade} 통합 테스트.
 *
 * <p>대기열 진입 앞단의 RateLimiter 동작을 검증한다.
 * {@link EventQueueService}를 직접 조회해 대기열 상태를 확인한다.
 *
 * <p>검증 항목
 * <ul>
 *   <li>정상 진입 — tryJoin 성공 시 대기열에 등록되는지</li>
 *   <li>중복 진입 방지 — 같은 회원이 두 번 시도해도 한 번만 등록되는지</li>
 *   <li>RateLimiter 제한 — 초당 100명 초과 요청 시 일부가 거절(false)되는지</li>
 *   <li>cleanup — RateLimiter와 대기열이 함께 삭제되는지</li>
 * </ul>
 *
 * <p>인프라: Testcontainers(PostgreSQL·Redis) + EmbeddedKafka.
 * 테스트 격리: {@code @AfterEach}에서 {@code cleanup()}으로 Redis 키를 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class EventQueueFacadeTest extends TestContainerBase {

    @Autowired
    private EventQueueFacade eventQueueFacade;

    @Autowired
    private EventQueueService eventQueueService;

    private static final Long EVENT_ID = 200L;

    @AfterEach
    void tearDown() {
        eventQueueFacade.cleanup(EVENT_ID);
    }

    @Test
    @DisplayName("tryJoin 성공 시 대기열에 등록된다")
    void tryJoin_성공_시_대기열_등록() {
        boolean result = eventQueueFacade.tryJoin(EVENT_ID, 1L);

        assertThat(result).isTrue();
        assertThat(eventQueueService.isInQueue(EVENT_ID, 1L)).isTrue();
    }

    @Test
    @DisplayName("같은 회원이 두 번 tryJoin해도 대기열에 한 번만 등록된다")
    void 중복_tryJoin_한번만_등록() {
        eventQueueFacade.tryJoin(EVENT_ID, 1L);
        eventQueueFacade.tryJoin(EVENT_ID, 1L);

        assertThat(eventQueueService.getSize(EVENT_ID)).isEqualTo(1);
        assertThat(eventQueueService.getPosition(EVENT_ID, 1L)).isEqualTo(0);
    }

    @Test
    @DisplayName("cleanup 후 대기열이 비워진다")
    void cleanup_후_대기열_초기화() {
        eventQueueFacade.tryJoin(EVENT_ID, 1L);
        eventQueueFacade.tryJoin(EVENT_ID, 2L);

        eventQueueFacade.cleanup(EVENT_ID);

        assertThat(eventQueueService.getSize(EVENT_ID)).isEqualTo(0);
    }

    @Test
    @DisplayName("initRateLimiter 호출 후 tryJoin이 정상 동작한다")
    void initRateLimiter_후_tryJoin_정상동작() {
        eventQueueFacade.initRateLimiter(EVENT_ID);

        boolean result = eventQueueFacade.tryJoin(EVENT_ID, 10L);

        assertThat(result).isTrue();
        assertThat(eventQueueService.isInQueue(EVENT_ID, 10L)).isTrue();
    }

    @Test
    @DisplayName("초당 허용 인원(100명)을 초과하면 일부 tryJoin이 false를 반환한다")
    void 초당_허용_인원_초과_시_일부_거절() throws InterruptedException {
        // RateLimiter: 초당 100명 제한
        // 200명을 1초 안에 동시 요청하면 일부는 false 반환되어야 함
        int total = 200;
        int[] results = new int[]{0, 0}; // [성공, 실패]

        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(total);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done  = new java.util.concurrent.CountDownLatch(total);
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(total);

        for (int i = 0; i < total; i++) {
            final long memberId = i + 1L;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (eventQueueFacade.tryJoin(EVENT_ID, memberId)) {
                        synchronized (results) { results[0]++; }
                    } else {
                        synchronized (results) { results[1]++; }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(10, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // 100명은 통과, 나머지는 거절
        assertThat(results[0]).isLessThanOrEqualTo(100);
        assertThat(results[1]).isGreaterThan(0);
    }
}
