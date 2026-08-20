package com.whale.order.global.outbox;

import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
@Transactional
class KafkaOutboxRepositoryTest extends TestContainerBase {

    @Autowired KafkaOutboxRepository repository;

    @BeforeEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void findTop100ByStatus_는_PENDING만_생성순으로_반환() {
        KafkaOutbox first = KafkaOutbox.enqueue("t", 1L, "{}");
        KafkaOutbox second = KafkaOutbox.enqueue("t", 2L, "{}");
        KafkaOutbox published = KafkaOutbox.enqueue("t", 3L, "{}");
        published.markPublished();
        repository.saveAll(List.of(first, second, published));

        List<KafkaOutbox> result = repository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(KafkaOutbox::getAggregateId).containsExactly(1L, 2L);
    }

    @Test
    void deleteByStatusAndPublishedAtBefore_는_기준시각_이전_PUBLISHED만_삭제() {
        KafkaOutbox oldPub = repository.save(KafkaOutbox.enqueue("t", 1L, "{}"));
        oldPub.markPublished();
        repository.save(oldPub);
        repository.updatePublishedAtForTest(oldPub.getId(), LocalDateTime.now().minusDays(8));

        KafkaOutbox recentPub = repository.save(KafkaOutbox.enqueue("t", 2L, "{}"));
        recentPub.markPublished();
        repository.save(recentPub);

        repository.save(KafkaOutbox.enqueue("t", 3L, "{}")); // PENDING — 유지

        long deleted = repository.deleteByStatusAndPublishedAtBefore(
                OutboxStatus.PUBLISHED, LocalDateTime.now().minusDays(7));

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void countByStatus_는_상태별_건수_반환() {
        KafkaOutbox a = KafkaOutbox.enqueue("t", 1L, "{}");
        KafkaOutbox b = KafkaOutbox.enqueue("t", 2L, "{}");
        KafkaOutbox c = KafkaOutbox.enqueue("t", 3L, "{}");
        c.markPublished();
        repository.saveAll(List.of(a, b, c));

        assertThat(repository.countByStatus(OutboxStatus.PENDING)).isEqualTo(2);
        assertThat(repository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(1);
    }
}