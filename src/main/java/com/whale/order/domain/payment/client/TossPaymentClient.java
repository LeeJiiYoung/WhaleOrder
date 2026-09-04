package com.whale.order.domain.payment.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 토스페이먼츠 결제 승인 API 클라이언트.
 *
 * <p>토스는 시크릿 키를 Basic 인증의 사용자 ID로 쓰고 비밀번호는 비워둔다
 * ("{secretKey}:" 형태를 그대로 Base64 인코딩).</p>
 *
 * <p>모든 요청/응답을 인터셉터로 로깅한다. Authorization 헤더(시크릿 키)는 절대 로그에 남기지 않고
 * 메서드·URI·바디만 남긴다 — 응답 스트림은 한 번만 읽을 수 있어서 {@link BufferingClientHttpRequestFactory}로
 * 감싸 로깅 후에도 실제 역직렬화(retrieve().body(...))가 정상적으로 다시 읽을 수 있게 한다.</p>
 */
@Slf4j
@Component
public class TossPaymentClient {

    private final RestClient restClient;

    public TossPaymentClient(@Value("${toss.secret-key}") String secretKey) {
        String encoded = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));

        this.restClient = RestClient.builder()
                .baseUrl("https://api.tosspayments.com")
                .defaultHeader("Authorization", "Basic " + encoded)
                .requestFactory(new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()))
                .requestInterceptor(loggingInterceptor())
                .build();
    }

    private ClientHttpRequestInterceptor loggingInterceptor() {
        return (request, body, execution) -> {
            log.info("[토스API 요청] {} {} body={}",
                    request.getMethod(), request.getURI(), new String(body, StandardCharsets.UTF_8));

            ClientHttpResponse response = execution.execute(request, body);

            String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
            log.info("[토스API 응답] {} {} status={} body={}",
                    request.getMethod(), request.getURI(), response.getStatusCode(), responseBody);

            return response;
        };
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

    /**
     * 결제 취소(환불) 요청.
     *
     * <p>토스가 4xx/5xx 로 응답하면 {@link org.springframework.web.client.RestClientResponseException}이
     * 던져진다 — 호출부에서 "실제로 환불이 안 됐는데 우리 DB만 CANCELLED로 바뀌는" 불일치를
     * 막기 위해 그대로 상위로 전파해 트랜잭션을 롤백시키는 데 쓴다.</p>
     */
    public TossCancelResponse cancel(String paymentKey, String cancelReason) {
        return restClient.post()
                .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cancelReason", cancelReason))
                .retrieve()
                .body(TossCancelResponse.class);
    }
}
