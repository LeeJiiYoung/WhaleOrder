package com.whale.order.domain.member.repository;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // 자체 로그인 ID로 조회
    Optional<Member> findByUserId(String userId);

    // 카카오 소셜 로그인 회원 조회
    Optional<Member> findByProviderAndProviderId(AuthProvider provider, String providerId);

    // 자체 로그인 ID 중복 확인
    boolean existsByUserId(String userId);

    // 특정 역할 + 아이디/이름 키워드 검색 (최대 20건)
    @Query("SELECT m FROM Member m WHERE m.role = :role AND (" +
           "LOWER(COALESCE(m.userId, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<Member> searchByRoleAndKeyword(@Param("role") MemberRole role,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);

    // 전체 회원 목록 - 키워드/역할 필터 (어드민 회원 관리)
    @Query("SELECT m FROM Member m WHERE " +
           "(:keyword IS NULL OR :keyword = '' OR " +
           " LOWER(COALESCE(m.userId, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:role IS NULL OR m.role = :role) " +
           "ORDER BY m.createdAt DESC")
    List<Member> findAllWithFilters(@Param("keyword") String keyword,
                                    @Param("role") MemberRole role);
}