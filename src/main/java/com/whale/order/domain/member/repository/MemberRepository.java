package com.whale.order.domain.member.repository;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // 자체 로그인 ID로 조회
    Optional<Member> findByUserId(String userId);

    // 카카오 소셜 로그인 회원 조회
    Optional<Member> findByProviderAndProviderId(AuthProvider provider, String providerId);

    // 자체 로그인 ID 중복 확인
    boolean existsByUserId(String userId);
}