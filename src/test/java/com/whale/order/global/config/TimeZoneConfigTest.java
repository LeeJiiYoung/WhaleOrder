package com.whale.order.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TimeZoneConfig} 단위 테스트.
 *
 * <p>배포 서버(UTC) 등 어떤 환경에서 실행해도 앱 기본 타임존이 KST로 고정되는지 검증한다.
 * 이 고정이 없으면 {@code StoreStatusScheduler}의 영업시간 판정이 9시간 어긋나
 * 매장이 잘못된 시각에 자동 마감된다.
 */
class TimeZoneConfigTest {

    @Test
    @DisplayName("앱 기본 타임존이 Asia/Seoul(KST)로 고정된다")
    void 기본_타임존이_KST로_고정된다() {
        // when: 설정의 초기화 로직 실행
        new TimeZoneConfig().setDefaultTimeZone();

        // then: 실행 환경과 무관하게 기본 타임존이 KST
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
    }
}
