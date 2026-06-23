package com.whale.order.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 통합 테스트 공통 베이스 클래스.
 *
 * <p>PostgreSQL과 Redis를 Testcontainers로 띄우고
 * Spring의 {@code @DynamicPropertySource}로 접속 정보를 주입한다.
 *
 * <p>컨테이너를 {@code static}으로 선언해 JVM 전체에서 한 번만 시작하고 공유한다.
 * 이를 통해 테스트 클래스마다 컨테이너를 재시작하는 오버헤드를 제거한다.
 *
 * <p>컨테이너를 공유하므로 DB 데이터가 테스트 클래스 간에 누적된다. 매 테스트 전
 * {@code @BeforeEach}에서 모든 테이블을 {@code TRUNCATE ... RESTART IDENTITY CASCADE}로
 * 비워 테스트 간 완전 격리를 보장한다. (CASCADE 로 FK 순서 무관 정리, IDENTITY 초기화로 시퀀스 리셋)
 *
 * <p>Kafka는 각 테스트 클래스에서 {@code @EmbeddedKafka}로 인메모리 브로커를 사용하므로
 * 이 베이스 클래스에서 별도로 컨테이너를 관리하지 않는다.
 */
@SuppressWarnings("resource")
public abstract class TestContainerBase {

    static final PostgreSQLContainer<?> POSTGRES;
    static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

        // 두 컨테이너를 병렬로 시작해 대기 시간 단축
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 매 테스트 전 public 스키마의 모든 테이블을 비운다.
     * 공유 컨테이너에서 다른 테스트가 남긴 데이터(특히 FK로 얽힌 orders/order_item 등)가
     * deleteAll 순서 문제로 제약 위반을 일으키는 것을 원천 차단한다.
     */
    @BeforeEach
    void truncateAllTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        if (tables.isEmpty()) return;
        String joined = tables.stream()
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(", "));
        jdbcTemplate.execute("TRUNCATE TABLE " + joined + " RESTART IDENTITY CASCADE");
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
