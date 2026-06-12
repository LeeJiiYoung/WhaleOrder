package com.whale.order.global.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
public class RequestLoggingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req  = (HttpServletRequest)  request;
        HttpServletResponse res  = (HttpServletResponse) response;

        // SSE, actuator, 정적 리소스는 로그 제외
        String uri = req.getRequestURI();
        if (uri.contains("/stream") || uri.contains("/actuator")
                || uri.contains("/uploads") || uri.startsWith("/admin")
                || uri.startsWith("/demo") || uri.contains("/swagger")
                || uri.contains("/v3/api-docs")) {
            chain.doFilter(request, response);
            return;
        }

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            log.info("[API] {} {} → {} ({}ms)",
                    req.getMethod(), uri, res.getStatus(), elapsed);
        }
    }
}
