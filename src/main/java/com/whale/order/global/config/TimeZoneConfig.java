package com.whale.order.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * 애플리케이션 기본 타임존을 KST(Asia/Seoul)로 고정한다.
 *
 * <p>배포 서버(EC2 컨테이너)는 기본 타임존이 UTC라, {@code LocalTime.now()} 기반의
 * 영업시간 판정({@code StoreStatusScheduler})이 실제 한국 시각과 9시간 어긋난다.
 * 그 결과 매장이 엉뚱한 시각에 열리거나, 영업시간인데도 자동 마감되는 문제가 발생한다.
 *
 * <p>JVM 기본 타임존 자체를 고정해, 실행 환경(로컬·dev 컨테이너·EC2)과 무관하게
 * 앱 전역의 시간 계산이 항상 KST 기준이 되도록 한다.
 */
@Configuration
public class TimeZoneConfig {

    @PostConstruct
    public void setDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }
}
