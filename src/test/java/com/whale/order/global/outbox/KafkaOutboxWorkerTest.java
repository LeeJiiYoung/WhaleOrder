package com.whale.order.global.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxWorkerTest {

    @Mock KafkaOutboxRepository repository;
    @Mock KafkaOutboxRowProcessor rowProcessor;
    @InjectMocks KafkaOutboxWorker worker;

    @Test
    void publishPending_은_PENDING_top100_각각_processOne_호출() {
        KafkaOutbox a = KafkaOutbox.enqueue("t", 1L, "{}");
        KafkaOutbox b = KafkaOutbox.enqueue("t", 2L, "{}");
        setId(a, 100L);
        setId(b, 200L);
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(a, b));

        worker.publishPending();

        verify(rowProcessor).processOne(100L);
        verify(rowProcessor).processOne(200L);
    }

    @Test
    void 한_row_처리_실패해도_다음_row_계속_진행() {
        KafkaOutbox a = KafkaOutbox.enqueue("t", 1L, "{}");
        KafkaOutbox b = KafkaOutbox.enqueue("t", 2L, "{}");
        setId(a, 1L);
        setId(b, 2L);
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(a, b));
        doThrow(new RuntimeException("db down")).when(rowProcessor).processOne(1L);

        worker.publishPending();   // 예외 안 던짐

        verify(rowProcessor).processOne(1L);
        verify(rowProcessor).processOne(2L);   // 계속 진행됨
    }

    @Test
    void PENDING_없으면_processOne_호출안함() {
        when(repository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of());

        worker.publishPending();

        verifyNoInteractions(rowProcessor);
    }

    // 테스트 편의: 리플렉션으로 id 세팅 (프로덕션 코드에서 사용 금지)
    private void setId(KafkaOutbox outbox, Long id) {
        try {
            Field field = KafkaOutbox.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(outbox, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}