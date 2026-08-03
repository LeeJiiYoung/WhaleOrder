package com.whale.order.global.auth;

import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    // 자체 로그인 시 Spring Security가 호출 (userId로 조회)
    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        Member member = memberRepository.findByUserIdAndIsDeletedFalse(userId)
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 아이디입니다: " + userId));
        return new CustomUserDetails(member);
    }

    // JWT 필터에서 호출 (토큰에서 꺼낸 memberId로 조회)
    // 탈퇴 회원은 남아있는 access token 으로도 인증되지 않아야 하므로 여기서 걸러낸다.
    public UserDetails loadUserByMemberId(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 회원입니다: " + memberId));
        return new CustomUserDetails(member);
    }
}
