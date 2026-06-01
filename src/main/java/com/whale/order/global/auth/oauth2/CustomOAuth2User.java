package com.whale.order.global.auth.oauth2;

import com.whale.order.global.auth.CustomUserDetails;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;

// OAuth2User + CustomUserDetails를 함께 들고 다니기 위한 래퍼
// SuccessHandler에서 Member 정보를 꺼내기 위해 사용
public class CustomOAuth2User implements OAuth2User {

    private final CustomUserDetails userDetails;
    private final Map<String, Object> attributes;

    public CustomOAuth2User(CustomUserDetails userDetails, Map<String, Object> attributes) {
        this.userDetails = userDetails;
        this.attributes = attributes;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return userDetails.getAuthorities();
    }

    // Spring Security가 principal 식별에 사용 — memberId 반환
    @Override
    public String getName() {
        return userDetails.getUsername();
    }

    public CustomUserDetails getUserDetails() {
        return userDetails;
    }
}
