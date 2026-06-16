package com.whale.order.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 통합 테스트 공통 베이스 클래스.
 *
 * <p>PostgreSQL과 Redis를 Testcontainers로 띄우고
 * Spring의 {@code @DynamicPropertySource}로 접속 정보를 주입한다.
 *
 * <p>컨테이너를 {@code static}으로 선언해 JVM 전체에서 한 번만 시작하고 공유한다.
 * 이를 통해 테스트 클래스마다 컨테이너를 재시작하는 오버헤드를 제거한다.
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

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
