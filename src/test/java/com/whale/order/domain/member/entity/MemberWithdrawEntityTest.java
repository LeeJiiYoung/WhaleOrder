package com.whale.order.domain.member.entity;

import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Member.withdraw() 익명화 규칙 검증.
 *
 * <p>withdraw() 는 memberId 를 익명화 값에 사용하므로 영속화 이후에만 의미가 있다.
 * 따라서 순수 단위 테스트가 아니라 저장 후 호출하는 통합 테스트로 작성한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class MemberWithdrawEntityTest extends TestContainerBase {

    @Autowired private MemberRepository memberRepository;

    @Test
    @DisplayName("LOCAL 회원 탈퇴 시 개인정보가 익명화되고 userId 가 반납된다")
    void LOCAL_회원_익명화() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("chulsoo").password("encoded").name("김철수")
                .nickname("철수").phone("010-1234-5678")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());
        Long id = member.getMemberId();

        // when
        member.withdraw(WithdrawReason.PRICE);

        // then
        assertThat(member.getUserId()).isEqualTo("deleted_" + id);
        assertThat(member.getPassword()).isNull();
        assertThat(member.getName()).isEqualTo("탈퇴한 회원");
        assertThat(member.getNickname()).isNull();
        assertThat(member.getPhone()).isNull();
        assertThat(member.isDeleted()).isTrue();
        // 탈퇴 사유·시각은 익명화 대상이 아니라 통계용으로 남는다
        assertThat(member.getWithdrawReason()).isEqualTo(WithdrawReason.PRICE);
        assertThat(member.getWithdrawnAt()).isNotNull();
        // 역할·프로바이더는 통계 목적으로 유지한다
        assertThat(member.getRole()).isEqualTo(MemberRole.CUSTOMER);
        assertThat(member.getProvider()).isEqualTo(AuthProvider.LOCAL);
        // LOCAL 은 providerId 가 원래 null 이므로 그대로 null
        assertThat(member.getProviderId()).isNull();
    }

    @Test
    @DisplayName("KAKAO 회원 탈퇴 시 providerId 도 반납돼 같은 카카오 계정으로 재가입할 수 있다")
    void KAKAO_회원_providerId_반납() {
        // given
        Member member = memberRepository.save(Member.builder()
                .name("김카카오").nickname("카카오닉")
                .provider(AuthProvider.KAKAO).providerId("1234567890")
                .role(MemberRole.CUSTOMER).build());
        Long id = member.getMemberId();

        // when
        member.withdraw(WithdrawReason.SERVICE_QUALITY);

        // then
        assertThat(member.getProviderId()).isEqualTo("deleted_" + id);
        assertThat(member.getUserId()).isEqualTo("deleted_" + id);
        assertThat(member.isDeleted()).isTrue();
        assertThat(member.getWithdrawReason()).isEqualTo(WithdrawReason.SERVICE_QUALITY);
    }

    @Test
    @DisplayName("사유를 고르지 않아도 탈퇴는 되고 사유만 null 로 남는다")
    void 사유_미응답_허용() {
        // given
        Member member = memberRepository.save(Member.builder()
                .userId("younghee").password("encoded").name("김영희")
                .provider(AuthProvider.LOCAL).role(MemberRole.CUSTOMER).build());

        // when: 사유를 안 고른 경우
        member.withdraw(null);

        // then: 탈퇴는 정상 처리되고 시각은 기록된다
        assertThat(member.isDeleted()).isTrue();
        assertThat(member.getWithdrawReason()).isNull();
        assertThat(member.getWithdrawnAt()).isNotNull();
    }
}
