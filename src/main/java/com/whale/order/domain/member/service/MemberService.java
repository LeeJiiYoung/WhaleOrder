package com.whale.order.domain.member.service;

import com.whale.order.domain.member.dto.*;
import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.global.auth.RefreshTokenService;
import com.whale.order.global.auth.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;

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

    @Transactional(readOnly = true)
    public List<MemberSearchResponse> searchOwners(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        return memberRepository
                .searchByRoleAndKeyword(MemberRole.OWNER, kw, PageRequest.of(0, 20))
                .stream()
                .map(MemberSearchResponse::from)
                .toList();
    }

    // ── 어드민 회원 관리 ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MemberResponse> getMembers(String keyword, MemberRole role) {
        String kw = (keyword == null) ? "" : keyword.trim();
        return memberRepository.findAllWithFilters(kw, role)
                .stream().map(MemberResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MemberResponse getMember(Long memberId) {
        return MemberResponse.from(findById(memberId));
    }

    @Transactional
    public MemberResponse createMember(AdminMemberCreateRequest req) {
        if (memberRepository.existsByUserId(req.userId())) {
            throw new IllegalArgumentException("이미 사용 중인 아이디입니다");
        }
        Member member = Member.builder()
                .userId(req.userId())
                .password(passwordEncoder.encode(req.password()))
                .name(req.name())
                .nickname(req.nickname())
                .phone(req.phone())
                .provider(AuthProvider.LOCAL)
                .role(req.role())
                .build();
        return MemberResponse.from(memberRepository.save(member));
    }

    @Transactional
    public MemberResponse updateMember(Long memberId, MemberUpdateRequest req) {
        Member member = findById(memberId);
        member.updateName(req.name());
        member.updateNickname(req.nickname());
        member.updatePhone(req.phone());
        member.updateRole(req.role());
        return MemberResponse.from(member);
    }

    @Transactional
    public void deleteMember(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new IllegalArgumentException("존재하지 않는 회원입니다");
        }
        memberRepository.deleteById(memberId);
    }

    @Transactional
    public void resetPassword(Long memberId) {
        Member member = findById(memberId);
        if (member.getProvider() != AuthProvider.LOCAL) {
            throw new IllegalArgumentException("소셜 로그인 회원은 비밀번호를 초기화할 수 없습니다");
        }
        String initialPassword = member.getUserId() + member.getUserId();
        member.updatePassword(passwordEncoder.encode(initialPassword));
    }

    // ── 내 정보 (고객 본인) ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public MemberResponse getMyProfile(Long memberId) {
        return MemberResponse.from(findById(memberId));
    }

    @Transactional
    public MemberResponse updateMyProfile(Long memberId, MyProfileUpdateRequest req) {
        Member member = findById(memberId);
        member.updateNickname(req.nickname());
        member.updatePhone(req.phone());
        return MemberResponse.from(member);
    }

    @Transactional
    public void changePassword(Long memberId, PasswordChangeRequest req) {
        Member member = findById(memberId);
        if (!passwordEncoder.matches(req.currentPassword(), member.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다");
        }
        member.updatePassword(passwordEncoder.encode(req.newPassword()));
    }

    private Member findById(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다"));
    }

    // RTR: 기존 Refresh Token 폐기 후 새 Access Token + Refresh Token 동시 발급
    public LoginResponse refresh(String refreshToken) {
        if (!jwtProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("유효하지 않은 리프레시 토큰입니다");
        }
        Long memberId = jwtProvider.getMemberId(refreshToken);
        String stored = refreshTokenService.get(memberId);
        if (!refreshToken.equals(stored)) {
            // 탈취 가능성 — 저장된 토큰도 즉시 삭제
            refreshTokenService.delete(memberId);
            throw new IllegalArgumentException("리프레시 토큰이 일치하지 않습니다");
        }
        Member member = findById(memberId);
        refreshTokenService.delete(memberId);
        return issueTokens(member);
    }

    public void logout(String refreshToken) {
        if (!jwtProvider.validateToken(refreshToken)) return;
        Long memberId = jwtProvider.getMemberId(refreshToken);
        refreshTokenService.delete(memberId);
    }

    private LoginResponse issueTokens(Member member) {
        String accessToken = jwtProvider.generateAccessToken(member.getMemberId(), member.getRole());
        String refreshToken = jwtProvider.generateRefreshToken(member.getMemberId());
        refreshTokenService.save(member.getMemberId(), refreshToken);
        return new LoginResponse(accessToken, refreshToken, member.getNickname(), member.getRole().name());
    }
}