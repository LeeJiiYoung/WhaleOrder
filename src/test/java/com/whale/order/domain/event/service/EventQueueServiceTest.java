package com.whale.order.domain.event.service;

import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EventQueueService} 통합 테스트.
 *
 * <p>이벤트 한정판매 대기열의 핵심 로직을 검증한다.
 * <pre>
 *   Redis 자료구조 : Sorted Set
 *   Key           : "event:queue:{eventId}"
 *   Value         : memberId
 *   Score         : join() 호출 시각(ms) → 작을수록 먼저 들어온 사람
 * </pre>
 *
 * <p>검증 항목
 * <ul>
 *   <li>순번 부여 — join 순서가 score 순서와 일치하는지</li>
 *   <li>중복 방지 — 같은 memberId 재등록 시 기존 순번 유지</li>
 *   <li>선착순 poll — pollNext가 score 오름차순으로 반환하는지</li>
 *   <li>동시 등록 — 여러 스레드가 동시에 join해도 중복 없이 N명만 등록되는지</li>
 * </ul>
 *
 * <p>인프라: Testcontainers(PostgreSQL·Redis) + EmbeddedKafka.
 * 테스트 격리: {@code @AfterEach}에서 대기열 키를 삭제한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class EventQueueServiceTest extends TestContainerBase {

    @Autowired
    private EventQueueService eventQueueService;

    private static final Long EVENT_ID = 100L;

    @AfterEach
    void tearDown() {
        eventQueueService.clear(EVENT_ID);
    }

    @Test
    @DisplayName("대기열 등록 후 순번이 0부터 시작한다")
    void 대기열_등록_후_첫번째_순번은_0() {
        eventQueueService.join(EVENT_ID, 1L);

        assertThat(eventQueueService.getPosition(EVENT_ID, 1L)).isEqualTo(0);
    }

    @Test
    @DisplayName("등록 순서대로 순번이 부여된다")
    void 등록_순서대로_순번_부여() throws InterruptedException {
        eventQueueService.join(EVENT_ID, 1L);
        Thread.sleep(2);
        eventQueueService.join(EVENT_ID, 2L);
        Thread.sleep(2);
        eventQueueService.join(EVENT_ID, 3L);

        assertThat(eventQueueService.getPosition(EVENT_ID, 1L)).isEqualTo(0);
        assertThat(eventQueueService.getPosition(EVENT_ID, 2L)).isEqualTo(1);
        assertThat(eventQueueService.getPosition(EVENT_ID, 3L)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 회원이 두 번 등록해도 기존 순번이 유지된다")
    void 중복_등록_시_기존_순번_유지() throws InterruptedException {
        eventQueueService.join(EVENT_ID, 1L);
        Thread.sleep(2);
        eventQueueService.join(EVENT_ID, 2L);
        Thread.sleep(2);
        // 1L 다시 join 시도
        eventQueueService.join(EVENT_ID, 1L);

        assertThat(eventQueueService.getPosition(EVENT_ID, 1L)).isEqualTo(0);
        assertThat(eventQueueService.getSize(EVENT_ID)).isEqualTo(2);
    }

    @Test
    @DisplayName("대기열에 없는 회원의 순번은 -1이다")
    void 미등록_회원_순번_마이너스1() {
        assertThat(eventQueueService.getPosition(EVENT_ID, 999L)).isEqualTo(-1);
    }

    @Test
    @DisplayName("isInQueue - 등록한 회원은 true, 미등록은 false")
    void isInQueue_등록_여부_확인() {
        eventQueueService.join(EVENT_ID, 50L);

        assertThat(eventQueueService.isInQueue(EVENT_ID, 50L)).isTrue();
        assertThat(eventQueueService.isInQueue(EVENT_ID, 99L)).isFalse();
    }

    @Test
    @DisplayName("pollNext로 N명을 꺼내면 대기열 크기가 줄어든다")
    void pollNext_후_대기열_크기_감소() throws InterruptedException {
        eventQueueService.join(EVENT_ID, 1L);
        Thread.sleep(2);
        eventQueueService.join(EVENT_ID, 2L);
        Thread.sleep(2);
        eventQueueService.join(EVENT_ID, 3L);

        eventQueueService.pollNext(EVENT_ID, 2);

        assertThat(eventQueueService.getSize(EVENT_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("pollNext는 먼저 들어온 순서대로 반환한다")
    void pollNext_선착순_반환() throws InterruptedException {
        eventQueueService.join(EVENT_ID, 10L);
        Thread.sleep(2);
        eventQueueService.join(EVENT_ID, 20L);
        Thread.sleep(2);
        eventQueueService.join(EVENT_ID, 30L);

        List<Long> polled = new ArrayList<>(eventQueueService.pollNext(EVENT_ID, 2));

        assertThat(polled).containsExactly(10L, 20L);
        // 30L은 대기열에 남아있음
        assertThat(eventQueueService.isInQueue(EVENT_ID, 30L)).isTrue();
    }

    @Test
    @DisplayName("clear 후 대기열이 비어있다")
    void clear_후_대기열_비어있음() {
        eventQueueService.join(EVENT_ID, 1L);
        eventQueueService.join(EVENT_ID, 2L);

        eventQueueService.clear(EVENT_ID);

        assertThat(eventQueueService.getSize(EVENT_ID)).isEqualTo(0);
    }

    @Test
    @DisplayName("동시에 N명이 join하면 대기열에 정확히 N명이 등록된다 (중복 없음)")
    void 동시_등록_중복없이_N명_등록() throws InterruptedException {
        int threadCount = 30;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final long memberId = i + 1L;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    eventQueueService.join(EVENT_ID, memberId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(eventQueueService.getSize(EVENT_ID)).isEqualTo(threadCount);
    }
}
