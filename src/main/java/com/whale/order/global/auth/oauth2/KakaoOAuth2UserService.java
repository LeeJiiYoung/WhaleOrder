package com.whale.order.global.auth.oauth2;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.global.auth.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class KakaoOAuth2UserService extends DefaultOAuth2UserService {

    private final MemberRepository memberRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        Map<String, Object> attributes = oAuth2User.getAttributes();

        // 카카오 응답: { id: 12345, kakao_account: { profile: { nickname: "..." } } }
        String providerId = String.valueOf(attributes.get("id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = kakaoAccount != null ? (Map<String, Object>) kakaoAccount.get("profile") : null;
        final String nickname = (profile != null) ? (String) profile.get("nickname") : null;

        Member member = memberRepository.findByProviderAndProviderId(AuthProvider.KAKAO, providerId)
                .map(existing -> {
                    // 카카오에서 닉네임이 바뀌었으면 동기화
                    if (nickname != null && !nickname.equals(existing.getNickname())) {
                        existing.updateNickname(nickname);
                    }
                    return existing;
                })
                .orElseGet(() -> memberRepository.save(Member.builder()
                        .name(nickname != null ? nickname : "카카오사용자")
                        .nickname(nickname)
                        .provider(AuthProvider.KAKAO)
                        .providerId(providerId)
                        .role(MemberRole.CUSTOMER)
                        .build()));

        return new CustomOAuth2User(new CustomUserDetails(member), attributes);
    }
}
