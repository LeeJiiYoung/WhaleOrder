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

/**
 * Member 엔티티에는 {@code @SQLRestriction} 이 없다. 탈퇴 회원을 막아야 하는 쿼리에만
 * {@code isDeleted = false} 를 명시적으로 건다. 파생 쿼리 대신 {@code @Query} 를 쓰는 이유는
 * boolean 필드명이 {@code isDeleted} 라 {@code IsDeletedFalse} 파싱이 모호해질 수 있어서다.
 */
public interface MemberRepository extends JpaRepository<Member, Long> {

    // 자체 로그인 ID로 조회 - 탈퇴 회원은 로그인할 수 없어야 하므로 제외한다
    @Query("SELECT m FROM Member m WHERE m.userId = :userId AND m.isDeleted = false")
    Optional<Member> findByUserIdAndIsDeletedFalse(@Param("userId") String userId);

    // 카카오 소셜 로그인 회원 조회 - 탈퇴 회원 제외 (재로그인 시 새 회원으로 가입된다)
    @Query("SELECT m FROM Member m WHERE m.provider = :provider AND m.providerId = :providerId " +
           "AND m.isDeleted = false")
    Optional<Member> findByProviderAndProviderIdAndIsDeletedFalse(@Param("provider") AuthProvider provider,
                                                                  @Param("providerId") String providerId);

    // 자체 로그인 ID 중복 확인 - 탈퇴 회원까지 포함해야 DB unique 제약과 검사 범위가 일치한다.
    // 탈퇴 시 userId 를 deleted_{memberId} 로 바꿔 슬롯을 반납하므로 재가입을 막지 않는다.
    boolean existsByUserId(String userId);

    // 특정 역할 + 아이디/이름 키워드 검색 (최대 20건)
    @Query("SELECT m FROM Member m WHERE m.role = :role AND m.isDeleted = false AND (" +
           "LOWER(COALESCE(m.userId, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<Member> searchByRoleAndKeyword(@Param("role") MemberRole role,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);

    // 전체 회원 목록 - 키워드/역할 필터 (어드민 회원 관리)
    @Query("SELECT m FROM Member m WHERE m.isDeleted = false AND " +
           "(:keyword IS NULL OR :keyword = '' OR " +
           " LOWER(COALESCE(m.userId, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(COALESCE(m.nickname, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:role IS NULL OR m.role = :role) " +
           "ORDER BY m.createdAt DESC")
    List<Member> findAllWithFilters(@Param("keyword") String keyword,
                                    @Param("role") MemberRole role);
}
