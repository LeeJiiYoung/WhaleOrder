-- ============================================================
-- WhaleOrder 테스트 데이터
-- DataInitializer.java 가 member 테이블 비어있을 때만 실행함
-- ============================================================

-- ────────────────────────────────────────────────
-- 1. 회원 (admin / customer / owner 2명)
-- ────────────────────────────────────────────────
-- 비밀번호 (BCrypt strength=10): 전부 adminadmin
INSERT INTO member (member_id, user_id, password, name, nickname, phone, provider, role, created_at, updated_at)
VALUES
    (1, 'admin',    '$2a$10$3qpkbjGCoFjuCTkZk5rZGeiv/DgLfEBBWzq5ZoRSWxaLbuMyUbO3u', '관리자',   '관리자',  '010-0000-0001', 'LOCAL', 'ADMIN',    NOW(), NOW()),
    (2, 'customer', '$2a$10$3qpkbjGCoFjuCTkZk5rZGeiv/DgLfEBBWzq5ZoRSWxaLbuMyUbO3u', '테스트고객', '고객님',  '010-0000-0002', 'LOCAL', 'CUSTOMER', NOW(), NOW()),
    (3, 'owner',    '$2a$10$3qpkbjGCoFjuCTkZk5rZGeiv/DgLfEBBWzq5ZoRSWxaLbuMyUbO3u', '점주김',   '점주',    '010-0000-0003', 'LOCAL', 'OWNER',    NOW(), NOW()),
    (4, 'owner2',   '$2a$10$3qpkbjGCoFjuCTkZk5rZGeiv/DgLfEBBWzq5ZoRSWxaLbuMyUbO3u', '점주박',   '점주2',   '010-0000-0004', 'LOCAL', 'OWNER',    NOW(), NOW())
ON CONFLICT (user_id) DO NOTHING;

SELECT setval('member_member_id_seq', (SELECT MAX(member_id) FROM member));

-- ────────────────────────────────────────────────
-- 2. 매장 (강남점/홍대점 → owner, 잠실점 → owner2)
-- ────────────────────────────────────────────────
INSERT INTO store (store_id, owner_id, name, postal_code, address, address_detail, phone,
                   open_time, close_time, status, latitude, longitude,
                   created_by, created_at, updated_by, updated_at)
VALUES
    (1, 3, '고래커피 강남점', '06236', '서울특별시 강남구 강남대로 390', '지하 1층',
     '02-1234-5001', '08:00', '22:00', 'OPEN', 37.4979, 127.0276, 3, NOW(), 3, NOW()),
    (2, 3, '고래커피 홍대점', '04031', '서울특별시 마포구 양화로 160', '1층',
     '02-1234-5002', '09:00', '23:00', 'OPEN', 37.5572, 126.9248, 3, NOW(), 3, NOW()),
    (3, 4, '고래커피 잠실점', '05551', '서울특별시 송파구 올림픽로 240', '2층',
     '02-1234-5003', '08:00', '22:00', 'OPEN', 37.5133, 127.1001, 4, NOW(), 4, NOW())
ON CONFLICT (store_id) DO NOTHING;

SELECT setval('store_store_id_seq', (SELECT MAX(store_id) FROM store));

-- ────────────────────────────────────────────────
-- 3. 메뉴
-- ────────────────────────────────────────────────
INSERT INTO menu (menu_id, name, description, base_price, category, is_active,
                  sale_start_date, sale_end_date, created_by, created_at, updated_by, updated_at)
VALUES
    (1, '아메리카노',       '에스프레소에 물을 더한 기본 블랙커피',           4500, 'BEVERAGE', true, '2026-01-01', '2026-12-31', 1, NOW(), 1, NOW()),
    (2, '카페라테',         '에스프레소와 스팀밀크의 조화',                  5500, 'BEVERAGE', true, '2026-01-01', '2026-12-31', 1, NOW(), 1, NOW()),
    (3, '카푸치노',         '에스프레소에 풍성한 우유 거품을 올린 클래식',     5500, 'BEVERAGE', true, '2026-01-01', '2026-12-31', 1, NOW(), 1, NOW()),
    (4, '그린티 라테',      '제주 녹차와 스팀밀크의 고소하고 깊은 맛',        6000, 'BEVERAGE', true, '2026-01-01', '2026-12-31', 1, NOW(), 1, NOW()),
    (5, '바닐라 라테',      '바닐라 시럽과 에스프레소의 달콤한 조화',         6000, 'BEVERAGE', true, '2026-01-01', '2026-12-31', 1, NOW(), 1, NOW()),
    (6, '뉴욕 치즈케이크',  '진한 크림치즈와 촉촉한 식감의 클래식 케이크',    7500, 'DESSERT',  true, '2026-01-01', '2026-12-31', 1, NOW(), 1, NOW()),
    (7, '아보카도 샌드위치','신선한 아보카도와 채소가 가득한 건강 샌드위치',   8500, 'FOOD',     true, '2026-01-01', '2026-12-31', 1, NOW(), 1, NOW())
ON CONFLICT (menu_id) DO NOTHING;

SELECT setval('menu_menu_id_seq', (SELECT MAX(menu_id) FROM menu));

-- ────────────────────────────────────────────────
-- 4. 메뉴 옵션 (음료 1~5번 공통)
-- ────────────────────────────────────────────────

-- SIZE (음료 전체) — 필수
INSERT INTO menu_option (menu_id, option_group, option_name, additional_price, is_required, created_by, created_at, updated_by, updated_at)
SELECT m.menu_id, 'SIZE', v.option_name, v.additional_price, true, 1, NOW(), 1, NOW()
FROM   (VALUES (1),(2),(3),(4),(5)) AS m(menu_id)
CROSS JOIN (VALUES ('TALL', 0),('GRANDE', 500),('VENTI', 1000)) AS v(option_name, additional_price)
WHERE NOT EXISTS (
    SELECT 1 FROM menu_option mo
    WHERE mo.menu_id = m.menu_id AND mo.option_group = 'SIZE' AND mo.option_name = v.option_name
);

-- TEMPERATURE (음료 전체) — 필수
INSERT INTO menu_option (menu_id, option_group, option_name, additional_price, is_required, created_by, created_at, updated_by, updated_at)
SELECT m.menu_id, 'TEMPERATURE', v.option_name, v.additional_price, true, 1, NOW(), 1, NOW()
FROM   (VALUES (1),(2),(3),(4),(5)) AS m(menu_id)
CROSS JOIN (VALUES ('HOT', 0),('ICED', 0)) AS v(option_name, additional_price)
WHERE NOT EXISTS (
    SELECT 1 FROM menu_option mo
    WHERE mo.menu_id = m.menu_id AND mo.option_group = 'TEMPERATURE' AND mo.option_name = v.option_name
);

-- SHOT (에스프레소 기반: 아메리카노·카페라테·카푸치노)
INSERT INTO menu_option (menu_id, option_group, option_name, additional_price, created_by, created_at, updated_by, updated_at)
SELECT m.menu_id, 'SHOT', v.option_name, v.additional_price, 1, NOW(), 1, NOW()
FROM   (VALUES (1),(2),(3)) AS m(menu_id)
CROSS JOIN (VALUES ('기본', 0),('추가 1샷', 500)) AS v(option_name, additional_price)
WHERE NOT EXISTS (
    SELECT 1 FROM menu_option mo
    WHERE mo.menu_id = m.menu_id AND mo.option_group = 'SHOT' AND mo.option_name = v.option_name
);

-- SYRUP (라테류: 카페라테·그린티 라테·바닐라 라테)
INSERT INTO menu_option (menu_id, option_group, option_name, additional_price, created_by, created_at, updated_by, updated_at)
SELECT m.menu_id, 'SYRUP', v.option_name, v.additional_price, 1, NOW(), 1, NOW()
FROM   (VALUES (2),(4),(5)) AS m(menu_id)
CROSS JOIN (VALUES ('바닐라', 500),('카라멜', 500),('헤이즐넛', 500)) AS v(option_name, additional_price)
WHERE NOT EXISTS (
    SELECT 1 FROM menu_option mo
    WHERE mo.menu_id = m.menu_id AND mo.option_group = 'SYRUP' AND mo.option_name = v.option_name
);

-- ────────────────────────────────────────────────
-- 5. 메뉴 할인 (아메리카노 500원 할인)
-- ────────────────────────────────────────────────
INSERT INTO menu_discount (menu_id, discount_amount, start_date, end_date, created_by, created_at, updated_by, updated_at)
SELECT 1, 500, '2026-06-01', '2026-07-31', 1, NOW(), 1, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM menu_discount WHERE menu_id = 1 AND start_date = '2026-06-01'
);

-- ────────────────────────────────────────────────
-- 6. 재고 (매장별 전 메뉴 100개)
-- ────────────────────────────────────────────────
INSERT INTO stock (store_id, menu_id, quantity, created_by, created_at, updated_by, updated_at)
SELECT s.store_id, m.menu_id, 100,
       CASE s.store_id WHEN 3 THEN 4 ELSE 3 END, NOW(),
       CASE s.store_id WHEN 3 THEN 4 ELSE 3 END, NOW()
FROM   (VALUES (1),(2),(3)) AS s(store_id)
CROSS JOIN (VALUES (1),(2),(3),(4),(5),(6),(7)) AS m(menu_id)
ON CONFLICT (store_id, menu_id) DO NOTHING;

-- @Version(낙관적 락) 백필 — version 이 NULL 이면 모든 재고 UPDATE 가 StaleObjectStateException(500) 으로 깨진다.
-- 기존에 version 없이 시드된 row 를 0 으로 보정 (재부팅 시 idempotent).
UPDATE stock SET version = 0 WHERE version IS NULL;

-- ────────────────────────────────────────────────
-- 7. 샘플 주문 (완료 1건 / 대기 1건)
-- ────────────────────────────────────────────────
INSERT INTO orders (order_id, member_id, store_id, status, total_price, order_type,
                    customer_request, stock_deducted, created_by, created_at, updated_by, updated_at)
VALUES
    (1, 2, 1, 'COMPLETED',  5000, 'TAKEOUT', '얼음 적게 부탁드려요', true,
     2, NOW() - INTERVAL '2 hours',      2, NOW() - INTERVAL '1 hour'),
    (2, 2, 2, 'PENDING',   11000, 'DINE_IN', NULL,                  false,
     2, NOW() - INTERVAL '10 minutes',   2, NOW() - INTERVAL '10 minutes')
ON CONFLICT (order_id) DO NOTHING;

SELECT setval('orders_order_id_seq', (SELECT MAX(order_id) FROM orders));

-- ────────────────────────────────────────────────
-- 8. 주문 항목
-- ────────────────────────────────────────────────
INSERT INTO order_item (order_id, menu_id, quantity, unit_price, options, created_by, created_at, updated_by, updated_at)
SELECT 1, 1, 1, 5000,
       '[{"group":"SIZE","name":"GRANDE","additionalPrice":500},{"group":"TEMPERATURE","name":"HOT","additionalPrice":0}]'::jsonb,
       2, NOW() - INTERVAL '2 hours', 2, NOW() - INTERVAL '2 hours'
WHERE NOT EXISTS (SELECT 1 FROM order_item WHERE order_id = 1 AND menu_id = 1);

INSERT INTO order_item (order_id, menu_id, quantity, unit_price, options, created_by, created_at, updated_by, updated_at)
SELECT 2, 2, 2, 5500,
       '[{"group":"SIZE","name":"TALL","additionalPrice":0},{"group":"TEMPERATURE","name":"ICED","additionalPrice":0}]'::jsonb,
       2, NOW() - INTERVAL '10 minutes', 2, NOW() - INTERVAL '10 minutes'
WHERE NOT EXISTS (SELECT 1 FROM order_item WHERE order_id = 2 AND menu_id = 2);

-- ────────────────────────────────────────────────
-- 9. 주문 상태 이력
-- ────────────────────────────────────────────────
INSERT INTO order_status_history (order_id, status, changed_by, changed_at)
SELECT v.order_id, v.status, v.changed_by, v.changed_at
FROM (VALUES
    (1, 'PENDING',   2, NOW() - INTERVAL '2 hours'),
    (1, 'PREPARING', 1, NOW() - INTERVAL '110 minutes'),
    (1, 'COMPLETED', 1, NOW() - INTERVAL '60 minutes')
) AS v(order_id, status, changed_by, changed_at)
WHERE NOT EXISTS (
    SELECT 1 FROM order_status_history osh
    WHERE osh.order_id = v.order_id AND osh.status = v.status
);

-- ────────────────────────────────────────────────
-- 10. 결제
-- ────────────────────────────────────────────────
INSERT INTO payment (payment_id, order_id, member_id, amount, method, status, external_tx_id,
                     created_by, created_at, updated_by, updated_at)
VALUES
    (1, 1, 2,  5000, 'KAKAO_PAY',   'SUCCESS', 'KAKAO-TEST-TX-0001',
     2, NOW() - INTERVAL '2 hours',      2, NOW() - INTERVAL '2 hours'),
    (2, 2, 2, 11000, 'CREDIT_CARD', 'PENDING', NULL,
     2, NOW() - INTERVAL '10 minutes',   2, NOW() - INTERVAL '10 minutes')
ON CONFLICT (payment_id) DO NOTHING;

SELECT setval('payment_payment_id_seq', (SELECT MAX(payment_id) FROM payment));

-- ────────────────────────────────────────────────
-- 11. 결제 이력
-- ────────────────────────────────────────────────
INSERT INTO payment_history (payment_id, status, reason, changed_at)
SELECT v.payment_id, v.status, v.reason, v.changed_at
FROM (VALUES
    (1, 'PENDING', NULL, NOW() - INTERVAL '2 hours 5 minutes'),
    (1, 'SUCCESS', NULL, NOW() - INTERVAL '2 hours')
) AS v(payment_id, status, reason, changed_at)
WHERE NOT EXISTS (
    SELECT 1 FROM payment_history ph
    WHERE ph.payment_id = v.payment_id AND ph.status = v.status
);

-- ────────────────────────────────────────────────
-- 12. 굿즈
-- ────────────────────────────────────────────────
INSERT INTO goods (goods_id, store_id, name, description, price, image_url, created_by, created_at, updated_by, updated_at)
VALUES
    (1, 1, '고래 텀블러',   '고래 로고가 새겨진 스테인리스 보온·보냉 텀블러 (500ml)', 28000, NULL, 3, NOW(), 3, NOW()),
    (2, 1, '고래 머그컵',   '도자기 소재의 아트워크 머그컵 (350ml)',                  18000, NULL, 3, NOW(), 3, NOW()),
    (3, 2, '고래 에코백',   '유기농 면 소재 장바구니 에코백',                          15000, NULL, 3, NOW(), 3, NOW()),
    (4, 2, '고래 키링',     '고래 캐릭터 아크릴 키링',                                 9000, NULL, 3, NOW(), 3, NOW())
ON CONFLICT (goods_id) DO NOTHING;

SELECT setval('goods_goods_id_seq', (SELECT MAX(goods_id) FROM goods));

-- ────────────────────────────────────────────────
-- 13. 이벤트
-- ────────────────────────────────────────────────
INSERT INTO event (event_id, store_id, goods_id, name, open_at, capacity, per_person_limit,
                   status, remaining_capacity, created_by, created_at, updated_by, updated_at)
VALUES
    (1, 1, 1, '고래 텀블러 한정 판매',
     NOW() - INTERVAL '1 hour', 100, 2, 'OPEN',      47, 3, NOW() - INTERVAL '3 days', 3, NOW()),
    (2, 2, 3, '고래 에코백 선착순 이벤트',
     NOW() + INTERVAL '1 day',   50, 1, 'SCHEDULED',  0, 3, NOW() - INTERVAL '1 day',  3, NOW()),
    (3, 1, 2, '고래 머그컵 한정 판매',
     NOW() - INTERVAL '7 days',  30, 1, 'CLOSED',     0, 3, NOW() - INTERVAL '10 days', 3, NOW())
ON CONFLICT (event_id) DO NOTHING;

SELECT setval('event_event_id_seq', (SELECT MAX(event_id) FROM event));