-- ============================================================
-- WhaleOrder 부하 테스트용 시드
-- LoadTestDataInitializer 가 testuser1 미존재 시에만 실행
-- ============================================================

-- ────────────────────────────────────────────────
-- 1. 부하 테스트용 회원 50명 (testuser1 ~ testuser50, 비밀번호 adminadmin)
-- 멱등성 키가 memberId 를 포함하므로 VU 별로 다른 계정을 써야 진짜 동시성 테스트가 됨
-- ────────────────────────────────────────────────
INSERT INTO member (member_id, user_id, password, name, nickname, phone, provider, role, created_at, updated_at)
SELECT 100 + gs,
       'testuser' || gs,
       '$2a$10$3qpkbjGCoFjuCTkZk5rZGeiv/DgLfEBBWzq5ZoRSWxaLbuMyUbO3u',
       '테스트' || gs,
       '테스터' || gs,
       '010-1000-' || LPAD(gs::text, 4, '0'),
       'LOCAL', 'CUSTOMER', NOW(), NOW()
FROM generate_series(1, 50) AS gs
ON CONFLICT (user_id) DO NOTHING;

SELECT setval('member_member_id_seq', (SELECT MAX(member_id) FROM member));

-- ────────────────────────────────────────────────
-- 2. 부하 테스트 전용 메뉴 — 옵션 없음, 단순 흐름 검증용
-- ────────────────────────────────────────────────
INSERT INTO menu (menu_id, name, description, base_price, category, is_active,
                  sale_start_date, sale_end_date, created_by, created_at, updated_by, updated_at)
VALUES
    (8, '테스트 음료', 'k6 부하 테스트 전용 (옵션 없음)', 4000, 'BEVERAGE', true,
     '2026-01-01', '2026-12-31', 1, NOW(), 1, NOW())
ON CONFLICT (menu_id) DO NOTHING;

SELECT setval('menu_menu_id_seq', (SELECT MAX(menu_id) FROM menu));

-- ────────────────────────────────────────────────
-- 3. 부하 테스트 메뉴 재고 — 무제한(-1) 으로 반복 실행에도 재고 소진 없음
-- ────────────────────────────────────────────────
INSERT INTO stock (store_id, menu_id, quantity, created_by, created_at, updated_by, updated_at)
SELECT s.store_id, 8, -1,
       CASE s.store_id WHEN 3 THEN 4 ELSE 3 END, NOW(),
       CASE s.store_id WHEN 3 THEN 4 ELSE 3 END, NOW()
FROM   (VALUES (1),(2),(3)) AS s(store_id)
ON CONFLICT (store_id, menu_id) DO NOTHING;
