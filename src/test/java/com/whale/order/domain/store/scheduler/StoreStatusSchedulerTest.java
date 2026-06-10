package com.whale.order.domain.store.scheduler;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.entity.StoreStatus;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매장 상태 자동 갱신 스케줄러 통합 테스트.
 *
 * <p>{@link StoreStatusScheduler#autoUpdateStoreStatus()}는 매 분 실행되어
 * 각 매장의 영업시간({@code openTime} ~ {@code closeTime})을 {@link LocalTime#now()}와 비교한 뒤
 * 상태를 {@code OPEN} / {@code CLOSED}로 자동 전환한다.
 *
 * <p>자정 교차 영업시간 처리: {@code openTime > closeTime}이면 자정을 넘기는 스케줄로 판단한다.
 * <pre>
 *   일반   : openTime.isBefore(closeTime) → now ∈ [open, close)
 *   자정교차 : openTime.isAfter(closeTime)  → now ≥ open OR now < close
 * </pre>
 *
 * <p>테스트 전략: 스케줄러를 자동 실행 주기에 맡기지 않고 {@code autoUpdateStoreStatus()}를
 * 직접 호출해 실행 타이밍 의존성을 제거한다.
 * 시각 의존적인 경계 조건은 "언제나 영업 중(00:00~23:59)"처럼 극단값을 사용해
 * 테스트를 최대한 결정론적으로 만든다.
 *
 * <p>인프라: Testcontainers(PostgreSQL) + EmbeddedKafka.
 * Redis는 사용하지 않지만 {@code TestContainerBase}가 함께 띄우므로 공유된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class StoreStatusSchedulerTest extends TestContainerBase {

    @Autowired private StoreStatusScheduler scheduler;
    @Autowired private StoreRepository      storeRepository;
    @Autowired private MemberRepository     memberRepository;

    private Member owner;

    @BeforeEach
    void setUp() {
        storeRepository.deleteAll();
        memberRepository.deleteAll();

        owner = memberRepository.save(Member.builder()
                .name("점주").provider(AuthProvider.LOCAL).role(MemberRole.OWNER).build());
    }

    @Test
    @DisplayName("영업 시간 내 CLOSED 매장은 자동으로 OPEN 된다")
    void 영업시간_내_CLOSED_매장_자동_오픈() {
        // given: 00:00 ~ 23:59 → 언제나 영업 중
        Store store = 매장_생성(LocalTime.of(0, 0), LocalTime.of(23, 59), StoreStatus.CLOSED);

        // when
        scheduler.autoUpdateStoreStatus();

        // then
        Store found = storeRepository.findById(store.getStoreId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(StoreStatus.OPEN);
    }

    @Test
    @DisplayName("영업 시간 외 OPEN 매장은 자동으로 CLOSED 된다")
    void 영업시간_외_OPEN_매장_자동_마감() {
        // given: 23:57 ~ 23:58 → 거의 항상 영업 외 시간
        Store store = 매장_생성(LocalTime.of(23, 57), LocalTime.of(23, 58), StoreStatus.OPEN);

        // when
        scheduler.autoUpdateStoreStatus();

        // then: 현재 시각이 23:57~23:58 사이가 아니면 CLOSED
        Store found = storeRepository.findById(store.getStoreId()).orElseThrow();
        LocalTime now = LocalTime.now();
        boolean inHours = !now.isBefore(LocalTime.of(23, 57)) && now.isBefore(LocalTime.of(23, 58));
        assertThat(found.getStatus()).isEqualTo(inHours ? StoreStatus.OPEN : StoreStatus.CLOSED);
    }

    @Test
    @DisplayName("이미 올바른 상태인 매장은 변경되지 않는다")
    void 이미_올바른_상태_변경_없음() {
        // given: 영업 중이고 이미 OPEN 상태
        Store store = 매장_생성(LocalTime.of(0, 0), LocalTime.of(23, 59), StoreStatus.OPEN);

        // when
        scheduler.autoUpdateStoreStatus();

        // then: OPEN 유지
        Store found = storeRepository.findById(store.getStoreId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(StoreStatus.OPEN);
    }

    @Test
    @DisplayName("자정을 넘기는 영업시간(22:00 ~ 02:00)에서 영업 중 판단이 올바르다")
    void 자정_넘기는_영업시간_처리() {
        // given: 22:00 ~ 02:00 영업 (자정 교차)
        LocalTime openTime  = LocalTime.of(22, 0);
        LocalTime closeTime = LocalTime.of(2, 0);
        LocalTime now = LocalTime.now();

        // 현재 시각 기준으로 기대 상태 계산
        boolean shouldBeOpen = now.isAfter(openTime) || now.isBefore(closeTime);
        StoreStatus initialStatus = shouldBeOpen ? StoreStatus.CLOSED : StoreStatus.OPEN;

        Store store = 매장_생성(openTime, closeTime, initialStatus);

        // when
        scheduler.autoUpdateStoreStatus();

        // then: 초기 상태와 반대로 전환되어야 함
        Store found = storeRepository.findById(store.getStoreId()).orElseThrow();
        StoreStatus expected = shouldBeOpen ? StoreStatus.OPEN : StoreStatus.CLOSED;
        assertThat(found.getStatus()).isEqualTo(expected);
    }

    @Test
    @DisplayName("여러 매장이 섞여 있어도 각각 독립적으로 상태가 갱신된다")
    void 여러_매장_독립_갱신() {
        // given: 항상 영업 중인 매장(CLOSED), 항상 영업 외 매장(OPEN)
        Store alwaysOpen   = 매장_생성(LocalTime.of(0, 0),  LocalTime.of(23, 59), StoreStatus.CLOSED);
        Store alwaysClosed = 매장_생성(LocalTime.of(23, 57), LocalTime.of(23, 58), StoreStatus.OPEN);

        // when
        scheduler.autoUpdateStoreStatus();

        // then
        assertThat(storeRepository.findById(alwaysOpen.getStoreId()).orElseThrow().getStatus())
                .isEqualTo(StoreStatus.OPEN);

        LocalTime now = LocalTime.now();
        boolean inNarrowWindow = !now.isBefore(LocalTime.of(23, 57)) && now.isBefore(LocalTime.of(23, 58));
        assertThat(storeRepository.findById(alwaysClosed.getStoreId()).orElseThrow().getStatus())
                .isEqualTo(inNarrowWindow ? StoreStatus.OPEN : StoreStatus.CLOSED);
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────
    // Store 빌더는 기본 상태를 CLOSED로 설정하므로, OPEN이 필요하면 open()을 추가 호출한다.
    // 매장 이름에 nanoTime을 붙여 테스트 간 이름 충돌을 방지한다.

    private Store 매장_생성(LocalTime openTime, LocalTime closeTime, StoreStatus status) {
        Store store = storeRepository.save(Store.builder()
                .owner(owner)
                .name("테스트 매장 " + System.nanoTime())
                .postalCode("12345")
                .address("서울시 강남구 테스트로 1")
                .openTime(openTime)
                .closeTime(closeTime)
                .build());

        if (status == StoreStatus.OPEN)  store.open();
        if (status == StoreStatus.CLOSED) store.close();
        return storeRepository.save(store);
    }
}
