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
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 분산 락과 트랜잭션 경계 재현 테스트.
 *
 * 바깥 @Transactional 안에서 StockLockFacade 를 호출하면 (OrderService.cancelOrder,
 * KafkaOutboxRowProcessor.processOne 이 이 구조) StockService 의 @Transactional(REQUIRED)이
 * 바깥 트랜잭션에 합류한다. 그 결과 재고 UPDATE 는 락이 풀린 뒤 바깥 커밋 시점에 실행된다.
 *
 * 바깥 트랜잭션은 TransactionTemplate 으로 흉내 낸다. 다른 스레드의 작업은 future.join()으로
 * 끝날 때까지 기다리므로 실행 순서가 고정되고, 타이밍에 따라 결과가 바뀌지 않는다.
 *
 * 현재 코드 기준 기대값으로 작성했다. 트랜잭션 경계를 고친 뒤에는 각 테스트의
 * "수정 후 기대값" 주석대로 단언을 바꾼다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"order-created", "order-created.DLT"})
class StockLockTransactionBoundaryTest extends TestContainerBase {

    @Autowired private StockLockFacade stockLockFacade;
    @Autowired private StockRepository stockRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private RedissonClient redissonClient;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate outerTx;
    private Long storeId;
    private Long menuId;
    private Long stockId;

    @BeforeEach
    void setUp() {
        // 테이블 초기화는 TestContainerBase 의 @BeforeEach(TRUNCATE)가 처리
        outerTx = new TransactionTemplate(transactionManager);

        Member owner = memberRepository.save(Member.builder()
                .name("점주")
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
                .basePrice(4500L)
                .category(MenuCategory.BEVERAGE)
                .build());

        Stock stock = stockRepository.save(Stock.builder()
                .store(store)
                .menu(menu)
                .quantity(10)
                .build());

        storeId = store.getStoreId();
        menuId = menu.getMenuId();
        stockId = stock.getStockId();
    }

    @Test
    @DisplayName("바깥 트랜잭션 안에서 차감하면 락이 풀린 시점에 DB에는 아직 반영되지 않는다")
    void deductInsideOuterTx_writeHappensAfterUnlock() {
        AtomicBoolean lockedAfterFacade = new AtomicBoolean(true);
        AtomicInteger quantitySeenByOtherTx = new AtomicInteger(-999);

        outerTx.executeWithoutResult(status -> {
            stockLockFacade.deductStock(storeId, menuId, 1);

            // facade 가 반환된 시점 = 락 해제 완료, 바깥 트랜잭션은 아직 커밋 전
            lockedAfterFacade.set(redissonClient.getLock(lockKey()).isLocked());

            // 다른 스레드(별도 트랜잭션)에서 커밋된 값을 읽는다
            CompletableFuture.runAsync(() ->
                    quantitySeenByOtherTx.set(stockRepository.findById(stockId).orElseThrow().getQuantity())
            ).join();
        });

        // 현재 코드: 락은 풀렸는데 DB 는 아직 10 → 락이 커밋을 보호하지 못하는 구간이 있다
        assertThat(lockedAfterFacade.get()).isFalse();
        assertThat(quantitySeenByOtherTx.get()).isEqualTo(10);
        // 수정 후 기대값: quantitySeenByOtherTx == 9 (락 해제 전에 커밋 완료)

        // 바깥 트랜잭션 커밋 후에는 정상 반영
        assertThat(stockRepository.findById(stockId).orElseThrow().getQuantity()).isEqualTo(9);
    }

    @Test
    @DisplayName("바깥 트랜잭션 안의 재고 복구는, 락 해제 후 끼어든 차감 때문에 커밋 시점에 충돌한다")
    void restoreInsideOuterTx_conflictsWithDeductAfterUnlock() {
        assertThatThrownBy(() ->
                outerTx.executeWithoutResult(status -> {
                    // cancelOrder 처럼 바깥 트랜잭션 안에서 복구 (version v 로 읽고 값만 변경)
                    stockLockFacade.restoreStock(storeId, menuId, 2);

                    // 락은 이미 풀렸으므로 다른 스레드의 차감이 락을 잡고 먼저 커밋한다 (version v+1)
                    CompletableFuture.runAsync(() ->
                            stockLockFacade.deductStock(storeId, menuId, 1)
                    ).join();

                    // 이 람다가 끝나면 바깥 커밋 → UPDATE ... WHERE version = v → 0건 → 충돌
                })
        ).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // 복구(+2)는 롤백되고 차감(-1)만 남는다.
        // 실제 cancelOrder 라면 이 시점에 토스 환불은 이미 실행된 상태다.
        assertThat(stockRepository.findById(stockId).orElseThrow().getQuantity()).isEqualTo(9);
        // 수정 후 기대값: 예외 없음, 최종 재고 11 (10 + 2 - 1)
    }

    private String lockKey() {
        return "stock:lock:" + storeId + ":" + menuId;
    }
}
