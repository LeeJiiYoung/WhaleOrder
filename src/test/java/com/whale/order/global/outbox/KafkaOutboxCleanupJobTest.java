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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
@Transactional
class KafkaOutboxCleanupJobTest extends TestContainerBase {

    @Autowired KafkaOutboxCleanupJob job;
    @Autowired KafkaOutboxRepository repository;

    @BeforeEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void cleanup_은_7일_지난_PUBLISHED만_삭제_최근건_유지_PENDING_유지() {
        // 8일 전 PUBLISHED — 삭제 대상
        KafkaOutbox oldPub = repository.save(KafkaOutbox.enqueue("t", 1L, "{}"));
        oldPub.markPublished();
        repository.save(oldPub);
        repository.updatePublishedAtForTest(oldPub.getId(), LocalDateTime.now().minusDays(8));

        // 3일 전 PUBLISHED — 유지
        KafkaOutbox recentPub = repository.save(KafkaOutbox.enqueue("t", 2L, "{}"));
        recentPub.markPublished();
        repository.save(recentPub);
        repository.updatePublishedAtForTest(recentPub.getId(), LocalDateTime.now().minusDays(3));

        // PENDING — 상태 조건 미충족으로 유지
        repository.save(KafkaOutbox.enqueue("t", 3L, "{}"));

        job.cleanup();

        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findById(oldPub.getId())).isEmpty();
    }
}