package com.whale.order.domain.member.service;

import com.whale.order.domain.member.dto.LoginRequest;
import com.whale.order.domain.member.dto.LoginResponse;
import com.whale.order.domain.member.dto.SignUpRequest;
import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.global.auth.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Transactional
    public LoginResponse signUp(SignUpRequest request) {
        if (memberRepository.existsByUserId(request.userId())) {
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다");
        }

        Member member = Member.builder()
                .userId(request.userId())
                .password(passwordEncoder.encode(request.password()))
                .name(request.name())
                .nickname(request.nickname())
                .phone(request.phone())
                .provider(AuthProvider.LOCAL)
                .role(MemberRole.CUSTOMER)
                .build();

        memberRepository.save(member);

        return issueTokens(member);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Member member = memberRepository.findByUserId(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("아이디 또는 비밀번호가 올바르지 않습니다"));

        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw new BadCredentialsException("아이디 또는 비밀번호가 올바르지 않습니다");
        }

        return issueTokens(member);
    }

    private LoginResponse issueTokens(Member member) {
        String accessToken = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());
        String refreshToken = jwtProvider.generateRefreshToken(member.getMemberId());
        return new LoginResponse(accessToken, refreshToken, member.getNickname(), member.getRole().name());
    }
}