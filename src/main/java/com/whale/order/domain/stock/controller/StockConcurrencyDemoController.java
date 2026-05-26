package com.whale.order.domain.stock.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.order.domain.stock.service.StockDemoService;
import com.whale.order.domain.stock.service.StockLockFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/demo/stock")
@RequiredArgsConstructor
public class StockConcurrencyDemoController {

    private final StockLockFacade stockLockFacade;
    private final StockDemoService stockDemoService;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(defaultValue = "10") int initialStock,
            @RequestParam(defaultValue = "30") int threadCount) {

        SseEmitter emitter = new SseEmitter(120_000L);

        executor.submit(() -> {
            try {
                long[] ids = stockDemoService.setupTestData(initialStock);
                long storeId = ids[0];
                long menuId = ids[1];

                send(emitter, "init", Map.of(
                        "initialStock", initialStock,
                        "threadCount", threadCount
                ));

                AtomicInteger successCount = new AtomicInteger();
                AtomicInteger failCount = new AtomicInteger();

                CountDownLatch ready = new CountDownLatch(threadCount);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(threadCount);
                ExecutorService pool = Executors.newFixedThreadPool(threadCount);

                for (int i = 0; i < threadCount; i++) {
                    final int tNum = i + 1;
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            stockLockFacade.deductStock(storeId, menuId, 1);
                            successCount.incrementAndGet();
                            int remaining = stockDemoService.getStock(storeId, menuId);
                            send(emitter, "result", Map.of(
                                    "threadId", tNum,
                                    "success", true,
                                    "remaining", remaining,
                                    "message", "재고 차감 성공"
                            ));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            failCount.incrementAndGet();
                            send(emitter, "result", Map.of(
                                    "threadId", tNum,
                                    "success", false,
                                    "remaining", -1,
                                    "message", e.getMessage()
                            ));
                        } finally {
                            done.countDown();
                        }
                    });
                }

                ready.await();
                start.countDown();
                done.await(60, TimeUnit.SECONDS);
                pool.shutdown();

                int finalStock = stockDemoService.getStock(storeId, menuId);
                send(emitter, "complete", Map.of(
                        "successCount", successCount.get(),
                        "failCount", failCount.get(),
                        "finalStock", finalStock
                ));
                emitter.complete();

            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 주문 대기열 데모
     * @param stock 재고
     * @param customers 고객
     * @return
     */
    @GetMapping(value = "/queue-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queueStream(
            @RequestParam(defaultValue = "10") int stock,
            @RequestParam(defaultValue = "20") int customers) {

        SseEmitter emitter = new SseEmitter(120_000L);

        executor.submit(() -> {
            try {
                long[] ids = stockDemoService.setupTestData(stock);
                long storeId = ids[0];
                long menuId = ids[1];

                send(emitter, "init", Map.of("stock", stock, "customers", customers));

                // 각 고객 스레드의 도착 시간 기록
                CopyOnWriteArrayList<long[]> arrivals = new CopyOnWriteArrayList<>();

                CountDownLatch ready = new CountDownLatch(customers);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(customers);
                ExecutorService pool = Executors.newFixedThreadPool(customers);

                for (int i = 0; i < customers; i++) {
                    final int cNum = i + 1;
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            // 동시 출발 후 도착 순서 기록
                            arrivals.add(new long[]{cNum, System.nanoTime()});
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }

                ready.await();
                start.countDown();
                done.await(10, TimeUnit.SECONDS);
                pool.shutdown();

                // 도착 순서로 정렬 → 대기열 결정
                List<long[]> queue = new ArrayList<>(arrivals);
                queue.sort(Comparator.comparingLong(a -> a[1]));

                // 대기열 확정 이벤트 전송
                for (int pos = 0; pos < queue.size(); pos++) {
                    send(emitter, "queued", Map.of(
                            "customerId", (int) queue.get(pos)[0],
                            "position", pos + 1
                    ));
                }

                // 순차 처리 (워커 시뮬레이션)
                int successCount = 0;
                int failCount = 0;
                for (int pos = 0; pos < queue.size(); pos++) {
                    int customerId = (int) queue.get(pos)[0];
                    Thread.sleep(300);
                    try {
                        stockLockFacade.deductStock(storeId, menuId, 1);
                        successCount++;
                        int remaining = stockDemoService.getStock(storeId, menuId);
                        send(emitter, "result", Map.of(
                                "customerId", customerId,
                                "position", pos + 1,
                                "success", true,
                                "remaining", remaining,
                                "message", "재고 차감 성공"
                        ));
                    } catch (Exception e) {
                        failCount++;
                        send(emitter, "result", Map.of(
                                "customerId", customerId,
                                "position", pos + 1,
                                "success", false,
                                "remaining", -1,
                                "message", "재고 부족"
                        ));
                    }
                }

                int finalStock = stockDemoService.getStock(storeId, menuId);
                send(emitter, "complete", Map.of(
                        "successCount", successCount,
                        "failCount", failCount,
                        "finalStock", finalStock
                ));
                emitter.complete();

            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void send(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(name).data(json));
            }
        } catch (IOException ignored) {}
    }
}
