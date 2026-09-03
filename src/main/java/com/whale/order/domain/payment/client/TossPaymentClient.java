package com.whale.order.domain.payment.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 토스페이먼츠 결제 승인 API 클라이언트.
 *
 * <p>토스는 시크릿 키를 Basic 인증의 사용자 ID로 쓰고 비밀번호는 비워둔다
 * ("{secretKey}:" 형태를 그대로 Base64 인코딩).</p>
 */
@Component
public class TossPaymentClient {

    private final RestClient restClient;

    public TossPaymentClient(@Value("${toss.secret-key}") String secretKey) {
        String encoded = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.restClient = RestClient.builder()
                .baseUrl("https://api.tosspayments.com")
                .defaultHeader("Authorization", "Basic " + encoded)
                .build();
    }

    /**
     * 결제 승인 요청.
     *
     * <p>토스가 4xx/5xx 로 응답하면 {@link org.springframework.web.client.RestClientResponseException}이
     * 던져진다 — 호출부에서 Payment.FAILED 전환(Saga 보상)에 사용한다.</p>
     */
    public TossConfirmResponse confirm(String paymentKey, String orderId, Long amount) {
        return restClient.post()
                .uri("/v1/payments/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "paymentKey", paymentKey,
                        "orderId", orderId,
                        "amount", amount
                ))
                .retrieve()
                .body(TossConfirmResponse.class);
    }
}
