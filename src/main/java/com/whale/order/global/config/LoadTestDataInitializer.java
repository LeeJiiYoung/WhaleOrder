package com.whale.order.global.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 부하 테스트(k6) 전용 시드.
 * <p>testuser1 의 존재 여부를 매 시작 시 체크해서 없으면 data-loadtest.sql 을 실행한다.
 * 일반 data.sql 과 분리한 이유:
 * <ul>
 *   <li>일반 시드는 "최초 생성" 이지만, 부하 테스트 시드는 "필요할 때마다 재확인" 성격</li>
 *   <li>기존 운영 데이터가 있어도 testuser 시드만 추가될 수 있게 가드 조건이 다름</li>
 * </ul>
 * prod 에는 절대 실행되지 않도록 {@code @Profile("dev")} 로 제한.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class LoadTestDataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member WHERE user_id = 'testuser1'", Integer.class);
        if (count != null && count > 0) {
            log.info("부하 테스트 시드 건너뜀 — testuser1 이미 존재");
            return;
        }

        log.info("부하 테스트 시드 삽입 시작 — testuser1~50, 메뉴 8, 메뉴 8 재고");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("data-loadtest.sql"));
        populator.execute(dataSource);
        log.info("부하 테스트 시드 삽입 완료");
    }
}
