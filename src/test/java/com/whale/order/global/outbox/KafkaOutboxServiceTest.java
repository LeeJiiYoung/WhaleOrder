package com.whale.order.global.outbox;

import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class KafkaOutboxServiceTest extends TestContainerBase {

    @Autowired KafkaOutboxService service;
    @Autowired KafkaOutboxRepository repository;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void enqueue_는_PENDING_row_저장() {
        KafkaOutbox saved = service.enqueue("order-created", 42L, "{\"orderId\":42}");

        assertThat(saved.getId()).isNotNull();
        assertThat(repository.count()).isEqualTo(1);

        KafkaOutbox loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(loaded.getTopic()).isEqualTo("order-created");
        assertThat(loaded.getAggregateId()).isEqualTo(42L);
    }

    @Test
    void enqueue_는_호출자_트랜잭션_롤백시_함께_롤백() {
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(status -> {
                    service.enqueue("order-created", 1L, "{}");
                    throw new RuntimeException("호출자 실패");
                })
        ).hasMessageContaining("호출자 실패");

        // 호출자 롤백 → outbox row 도 롤백되어 남아있지 않음
        assertThat(repository.count()).isZero();
    }
}