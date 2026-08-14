package com.whale.order.global.outbox;

import com.whale.order.domain.order.service.OrderKafkaProducer;
import com.whale.order.domain.order.service.OrderProcessingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxRowProcessorTest {

    @Mock KafkaOutboxRepository repository;
    @Mock OrderKafkaProducer kafkaProducer;
    @Mock OrderProcessingService processingService;
    @Mock KafkaOutboxMetrics metrics;

    @Test
    void kafka_있으면_send_호출_process_호출안함() {
        KafkaOutbox row = KafkaOutbox.enqueue("order-created", 42L, "{\"orderId\":42}");
        when(repository.findById(anyLong())).thenReturn(Optional.of(row));

        KafkaOutboxRowProcessor processor = new KafkaOutboxRowProcessor(
                repository, Optional.of(kafkaProducer), processingService, metrics);
        processor.processOne(1L);

        verify(kafkaProducer).publish(42L);
        verifyNoInteractions(processingService);
    }

    @Test
    void kafka_없으면_process_호출_send_시도안함() {
        KafkaOutbox row = KafkaOutbox.enqueue("order-created", 42L, "{\"orderId\":42}");
        when(repository.findById(anyLong())).thenReturn(Optional.of(row));

        KafkaOutboxRowProcessor processor = new KafkaOutboxRowProcessor(
                repository, Optional.empty(), processingService, metrics);
        processor.processOne(1L);

        verify(processingService).process(42L);
    }

    @Test
    void 발행_성공시_markPublished_published_지표_증가() {
        KafkaOutbox row = KafkaOutbox.enqueue("order-created", 42L, "{}");
        when(repository.findById(anyLong())).thenReturn(Optional.of(row));

        KafkaOutboxRowProcessor processor = new KafkaOutboxRowProcessor(
                repository, Optional.of(kafkaProducer), processingService, metrics);
        processor.processOne(1L);

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isNotNull();
        verify(metrics).incrementPublished();
        verify(metrics, never()).incrementFailed();
    }

    @Test
    void FAILED_전환_시에만_failed_지표_증가_재시도_중엔_증가안함() {
        KafkaOutbox row = KafkaOutbox.enqueue("order-created", 42L, "{}");
        // 이미 4회 실패한 상태로 세팅
        for (int i = 0; i < 4; i++) row.markFailedOrRetry(new RuntimeException("prev"), 5);
        when(repository.findById(anyLong())).thenReturn(Optional.of(row));
        doThrow(new RuntimeException("last")).when(kafkaProducer).publish(anyLong());

        KafkaOutboxRowProcessor processor = new KafkaOutboxRowProcessor(
                repository, Optional.of(kafkaProducer), processingService, metrics);
        processor.processOne(1L);

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        verify(metrics).incrementFailed();
    }

    @Test
    void 발행_실패시_attempts_증가_PENDING_유지() {
        KafkaOutbox row = KafkaOutbox.enqueue("order-created", 42L, "{}");
        when(repository.findById(anyLong())).thenReturn(Optional.of(row));
        doThrow(new RuntimeException("kafka down")).when(kafkaProducer).publish(anyLong());

        KafkaOutboxRowProcessor processor = new KafkaOutboxRowProcessor(
                repository, Optional.of(kafkaProducer), processingService, metrics);
        processor.processOne(1L);   // 예외 삼킴 (상태 업데이트 커밋을 위해)

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).contains("kafka down");
    }

    @Test
    void PENDING_아닌_row_는_스킵() {
        KafkaOutbox row = KafkaOutbox.enqueue("order-created", 42L, "{}");
        row.markPublished();
        when(repository.findById(anyLong())).thenReturn(Optional.of(row));

        KafkaOutboxRowProcessor processor = new KafkaOutboxRowProcessor(
                repository, Optional.of(kafkaProducer), processingService, metrics);
        processor.processOne(1L);

        verifyNoInteractions(kafkaProducer);
        verifyNoInteractions(processingService);
    }
}