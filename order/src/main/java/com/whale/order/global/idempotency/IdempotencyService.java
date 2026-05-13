package com.whale.order.global.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    /**
     * PROCESSING 레코드 삽입 시도.
     * ON CONFLICT DO NOTHING으로 원자적으로 처리하며, 타임아웃된 PROCESSING 레코드는 재획득한다.
     * @return true: 처리 권한 획득, false: 다른 요청이 처리 중이거나 이미 완료됨
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markProcessing(String key) {
        LocalDateTime now = LocalDateTime.now();
        int inserted = idempotencyRepository.insertProcessingIfAbsent(key, now, now.plusHours(24));
        if (inserted == 1) return true;

        // 타임아웃된 PROCESSING 레코드는 삭제 후 재획득 시도
        return idempotencyRepository.findById(key)
                .filter(IdempotencyRecord::isProcessingTimedOut)
                .map(record -> {
                    idempotencyRepository.delete(record);
                    idempotencyRepository.flush();
                    return idempotencyRepository.insertProcessingIfAbsent(key, now, now.plusHours(24)) == 1;
                })
                .orElse(false);
    }

    /**
     * 완료된 결과 조회.
     * PROCESSING 상태이거나 레코드가 없으면 null 반환.
     */
    @Transactional(readOnly = true)
    public <T> T getResult(String key, Class<T> type) {
        return idempotencyRepository.findById(key)
                .filter(r -> r.isCompleted() && !r.isExpired())
                .map(record -> deserialize(record.getResponseBody(), type))
                .orElse(null);
    }

    /**
     * 처리 완료 결과 저장 (PROCESSING → COMPLETED).
     * 메인 트랜잭션과 별도로 즉시 커밋한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> void saveResult(String key, T result) {
        idempotencyRepository.findById(key)
                .ifPresent(record -> record.complete(serialize(result)));
    }

    /**
     * 실패 시 키 삭제 → 클라이언트 재시도 허용.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(String key) {
        idempotencyRepository.deleteById(key);
    }

    private <T> String serialize(T obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("멱등성 결과 직렬화 실패", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("멱등성 결과 역직렬화 실패", e);
        }
    }
}
