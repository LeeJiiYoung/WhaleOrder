package com.whale.order.global.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Querydsl 설정.
 *
 * <p>JPQL 문자열로 표현하기 어려운 쿼리(동적 조건, 행 값 비교 등)를 타입 안전하게 작성하기 위해
 * {@link JPAQueryFactory} 를 빈으로 등록한다. 커스텀 리포지토리 구현체가 주입받아 사용한다.</p>
 */
@Configuration
public class QuerydslConfig {

    @PersistenceContext
    private EntityManager entityManager;

    @Bean
    public JPAQueryFactory jpaQueryFactory() {
        return new JPAQueryFactory(entityManager);
    }
}
