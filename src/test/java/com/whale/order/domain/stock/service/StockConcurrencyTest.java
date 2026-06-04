package com.whale.order.domain.stock.service;

import com.whale.order.domain.member.entity.AuthProvider;
import com.whale.order.domain.member.entity.Member;
import com.whale.order.domain.member.entity.MemberRole;
import com.whale.order.domain.member.repository.MemberRepository;
import com.whale.order.domain.menu.entity.Menu;
import com.whale.order.domain.menu.entity.MenuCategory;
import com.whale.order.domain.menu.repository.MenuRepository;
import com.whale.order.domain.stock.entity.Stock;
import com.whale.order.domain.stock.repository.StockRepository;
import com.whale.order.domain.store.entity.Store;
import com.whale.order.domain.store.repository.StoreRepository;
import com.whale.order.support.TestContainerBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class StockConcurrencyTest extends TestContainerBase {

    @Autowired StockLockFacade stockLockFacade;
    @Autowired StockRepository stockRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired MemberRepository memberRepository;

    private Long storeId;
    private Long menuId;

    @BeforeEach
    void setUp() {
        // 테스트 격리: 이전 테스트 데이터 삭제
        stockRepository.deleteAll();
        menuRepository.deleteAll();
        storeRepository.deleteAll();
        memberRepository.deleteAll();

        Member owner = memberRepository.save(Member.builder()
                .name("테스트 점주")
                .provider(AuthProvider.LOCAL)
                .role(MemberRole.OWNER)
                .build());

        Store store = storeRepository.save(Store.builder()
                .owner(owner)
                .name("테스트 매장")
                .postalCode("12345")
                .address("서울시 강남구 테스트로 1")
                .openTime(LocalTime.of(9, 0))
                .closeTime(LocalTime.of(21, 0))
                .build());

        Menu menu = menuRepository.save(Menu.builder()
                .name("아메리카노")
                .basePrice(4500)
                .category(MenuCategory.BEVERAGE)
                .build());

        storeId = store.getStoreId();
        menuId = menu.getMenuId();
    }

    @Test
    @DisplayName("재고 30개, 30개 스레드 동시 차감 → 최종 재고 0")
    void 동시_30개_요청_재고_정확히_0() throws InterruptedException {
        // Testcontainers 환경에서 컨테이너 네트워크 오버헤드를 감안해 30개로 제한
        // (락 타임아웃 5초 / 락 1회당 ~100ms = 안전 마진 확보)
        재고_설정(30);

        int threadCount = 30;
        동시_차감_실행(threadCount, 1);

        int remaining = 재고_조회();
        assertThat(remaining).isEqualTo(0);
    }

    @Test
    @DisplayName("재고 10개, 20개 스레드 동시 요청 → 10개 성공, 10개 실패")
    void 재고_초과_주문시_일부만_성공() throws InterruptedException {
        재고_설정(10);

        int threadCount = 20;
        AtomicInteger 성공 = new AtomicInteger();
        AtomicInteger 실패 = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    stockLockFacade.deductStock(storeId, menuId, 1);
                    성공.incrementAndGet();
                } catch (Exception e) {
                    실패.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown(); // 모든 스레드 동시 출발
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(성공.get()).isEqualTo(10);
        assertThat(실패.get()).isEqualTo(10);
        assertThat(재고_조회()).isEqualTo(0);
    }

    @Test
    @DisplayName("재고 차감 후 복구 → 원래 재고로 돌아옴")
    void 차감_후_복구_재고_원복() {
        재고_설정(50);

        stockLockFacade.deductStock(storeId, menuId, 20);
        assertThat(재고_조회()).isEqualTo(30);

        stockLockFacade.restoreStock(storeId, menuId, 20);
        assertThat(재고_조회()).isEqualTo(50);
    }

    @Test
    @DisplayName("무제한 재고(-1)는 차감해도 그대로 -1")
    void 무제한_재고는_차감_무시() throws InterruptedException {
        재고_설정(-1);

        int threadCount = 30;
        동시_차감_실행(threadCount, 1);

        assertThat(재고_조회()).isEqualTo(-1);
    }

    // --- 헬퍼 메서드 ---

    private void 재고_설정(int quantity) {
        stockRepository.save(Stock.builder()
                .store(storeRepository.findById(storeId).orElseThrow())
                .menu(menuRepository.findById(menuId).orElseThrow())
                .quantity(quantity)
                .build());
    }

    private void 동시_차감_실행(int threadCount, int amount) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    stockLockFacade.deductStock(storeId, menuId, amount);
                } catch (Exception ignored) {
                    // 재고 부족 등 예외는 이 헬퍼에서 무시
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();
    }

    private int 재고_조회() {
        return stockRepository.findByStore_StoreIdAndMenu_MenuId(storeId, menuId)
                .orElseThrow()
                .getQuantity();
    }
}
