package com.whale.order.global.idempotency;

import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멱등성 서비스 통합 테스트.
 *
 * 멱등성 키는 "동일 요청이 여러 번 들어와도 한 번만 처리"를 보장한다.
 * 핵심 메커니즘: PostgreSQL INSERT ... ON CONFLICT DO NOTHING
 *   - DB 레벨 원자성 → 애플리케이션 레벨 동기화(synchronized, Lock) 불필요
 *   - 여러 인스턴스(수평 확장)에서 동시에 요청이 들어와도 단 하나만 처리권 획득
 *
 * 테스트 흐름:
 *   markProcessing(key) → PROCESSING 레코드 삽입 시도
 *   saveResult(key, result) → COMPLETED 상태로 갱신 + 결과 직렬화 저장
 *   getResult(key, type) → COMPLETED 레코드만 역직렬화해 반환
 *   delete(key) → 실패 시 키 삭제 → 클라이언트 재시도 허용
 */
@SpringBootTest
@ActiveProfiles("test")
// KafkaConfig가 kafka.enabled 미설정 시 자동 로드되므로 EmbeddedKafka 필요
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class IdempotencyServiceTest extends TestContainerBase {

    @Autowired private IdempotencyService    idempotencyService;
    @Autowired private IdempotencyRepository idempotencyRepository;

    @BeforeEach
    void setUp() {
        // 테스트 간 키 충돌 방지 — 각 테스트는 UUID로 새 키를 만들지만 혹시 모를 잔여 데이터 제거
        idempotencyRepository.deleteAll();
    }

    // ── 동시성: DB 원자성으로 단 하나만 처리권 획득 ──────────────────────

    @Test
    @DisplayName("동일 키로 50개 스레드 동시 요청 시 단 하나만 처리 권한 획득")
    void 동일키_동시_요청_단하나만_처리권_획득() throws InterruptedException {
        String key = UUID.randomUUID().toString();
        int threadCount = 50;

        // ready: 모든 스레드가 준비 완료될 때까지 대기
        // start: 준비된 스레드를 동시에 출발시키는 스타팅 건
        // finish: 모든 스레드가 종료될 때까지 메인 스레드 대기
        CountDownLatch ready    = new CountDownLatch(threadCount);
        CountDownLatch start    = new CountDownLatch(1);
        CountDownLatch finish   = new CountDownLatch(threadCount);
        AtomicInteger  acquired = new AtomicInteger();
        ExecutorService pool    = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    // 모든 스레드가 준비되면 start.countDown() 으로 동시 출발
                    start.await();
                    if (idempotencyService.markProcessing(key)) {
                        // INSERT ON CONFLICT DO NOTHING → 오직 하나만 삽입 성공
                        acquired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finish.countDown();
                }
            });
        }

        ready.await();
        start.countDown(); // 50개 스레드 동시 출발
        finish.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(acquired.get())
            .as("ON CONFLICT DO NOTHING 원자성으로 단 하나만 처리 권한 획득")
            .isEqualTo(1);
    }

    // ── 결과 캐싱: 완료 후 재요청은 저장된 결과 반환 ─────────────────────

    @Test
    @DisplayName("처리 완료 결과 저장 후 동일 키 재요청 시 캐시된 결과 반환")
    void 완료_결과_저장_후_재요청_캐시_반환() {
        String key      = UUID.randomUUID().toString();
        String expected = "order-result-value";

        // 처리 시작 → 완료 결과 저장 (직렬화)
        idempotencyService.markProcessing(key);
        idempotencyService.saveResult(key, expected);

        // 재요청 시 DB에서 꺼내 역직렬화 → 동일 값
        String result = idempotencyService.getResult(key, String.class);
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("COMPLETED 상태 키는 markProcessing이 false를 반환해 중복 처리 차단")
    void COMPLETED_키_중복처리_차단() {
        String key = UUID.randomUUID().toString();

        idempotencyService.markProcessing(key);
        idempotencyService.saveResult(key, "result");

        // 완료된 키로 재처리 시도 → false 반환 → 호출자는 getResult로 캐시 응답을 그대로 반환
        boolean canProcess = idempotencyService.markProcessing(key);
        assertThat(canProcess)
            .as("완료된 키는 재처리 불가 → 클라이언트는 getResult로 캐시 응답 수령")
            .isFalse();
    }

    // ── 실패 복구: 키 삭제 후 재시도 허용 ───────────────────────────────

    @Test
    @DisplayName("처리 실패로 키 삭제 후 동일 키 재시도 허용")
    void 실패_후_키삭제_재시도_허용() {
        String key = UUID.randomUUID().toString();

        idempotencyService.markProcessing(key);
        // 서비스 레이어에서 예외 발생 시 catch 블록에서 delete() 호출 → 클라이언트 재시도 가능
        idempotencyService.delete(key);

        boolean canRetry = idempotencyService.markProcessing(key);
        assertThat(canRetry)
            .as("키 삭제 후에는 동일 키로 재처리 가능")
            .isTrue();
    }

    // ── 상태별 getResult 반환값 ──────────────────────────────────────────

    @Test
    @DisplayName("PROCESSING 상태에서 getResult는 null 반환")
    void PROCESSING_상태_결과조회_null() {
        String key = UUID.randomUUID().toString();
        // PROCESSING 상태 = 처리 중 → 아직 결과 없음
        idempotencyService.markProcessing(key);

        String result = idempotencyService.getResult(key, String.class);
        assertThat(result)
            .as("아직 처리 중이면 캐시 결과 없음")
            .isNull();
    }

    @Test
    @DisplayName("존재하지 않는 키 조회 시 null 반환")
    void 존재하지않는_키_조회_null() {
        // 레코드 자체가 없는 경우 → null (호출자가 새로 처리 시작)
        String result = idempotencyService.getResult(UUID.randomUUID().toString(), String.class);
        assertThat(result).isNull();
    }
}
