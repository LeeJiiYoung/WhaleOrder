package com.whale.order;

import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class OrderApplicationTests extends TestContainerBase {

	@Test
	void contextLoads() {
	}

}
