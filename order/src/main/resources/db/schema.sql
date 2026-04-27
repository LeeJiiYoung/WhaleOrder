-- ====================================
-- WhaleOrder DDL
-- Database: PostgreSQL
-- ====================================

-- 회원
CREATE TABLE member (
    member_id       BIGSERIAL       PRIMARY KEY,
    id              VARCHAR(100)    UNIQUE,
    password        VARCHAR(255),
    name            VARCHAR(50)     NOT NULL,
    nickname        VARCHAR(50),
    phone           VARCHAR(20),
    provider        VARCHAR(20)     NOT NULL,   -- LOCAL, KAKAO
    provider_id     VARCHAR(100),
    role            VARCHAR(20)     NOT NULL,   -- CUSTOMER, BARISTA, STORE_ADMIN, OWNER, ADMIN
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT uq_member_provider UNIQUE (provider, provider_id)
);

-- 매장
CREATE TABLE store (
    store_id        BIGSERIAL       PRIMARY KEY,
    owner_id        BIGINT          NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    postal_code     VARCHAR(10)     NOT NULL,
    address         VARCHAR(255)    NOT NULL,
    address_detail  VARCHAR(255),
    latitude        DECIMAL(10, 7)  NOT NULL,
    longitude       DECIMAL(10, 7)  NOT NULL,
    phone           VARCHAR(20),
    open_time       TIME            NOT NULL,
    close_time      TIME            NOT NULL,
    status          VARCHAR(20)     NOT NULL,   -- OPEN, CLOSED
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_store_owner FOREIGN KEY (owner_id) REFERENCES member (member_id)
);

-- 매장-직원 관계
CREATE TABLE store_member (
    store_member_id BIGSERIAL       PRIMARY KEY,
    store_id        BIGINT          NOT NULL,
    member_id       BIGINT          NOT NULL,
    role            VARCHAR(20)     NOT NULL,   -- BARISTA, STORE_ADMIN
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_store_member_store  FOREIGN KEY (store_id)  REFERENCES store  (store_id),
    CONSTRAINT fk_store_member_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT uq_store_member        UNIQUE (store_id, member_id)
);

-- 메뉴
CREATE TABLE menu (
    menu_id         BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL,
    description     TEXT,
    base_price      INTEGER         NOT NULL,
    category        VARCHAR(20)     NOT NULL,   -- BEVERAGE, FOOD, DESSERT
    image_url       VARCHAR(500),
    sale_start_date DATE,
    sale_end_date   DATE,
    is_active       BOOLEAN         NOT NULL DEFAULT true,
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT chk_menu_sale_date CHECK (sale_end_date >= sale_start_date)
);

-- 메뉴 옵션
CREATE TABLE menu_option (
    menu_option_id  BIGSERIAL       PRIMARY KEY,
    menu_id         BIGINT          NOT NULL,
    option_group    VARCHAR(50)     NOT NULL,   -- SIZE, SHOT, SYRUP, TEMPERATURE
    option_name     VARCHAR(50)     NOT NULL,
    additional_price INTEGER        NOT NULL DEFAULT 0,
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_menu_option_menu FOREIGN KEY (menu_id) REFERENCES menu (menu_id)
);

-- 메뉴 할인
CREATE TABLE menu_discount (
    discount_id     BIGSERIAL       PRIMARY KEY,
    menu_id         BIGINT          NOT NULL,
    discount_amount INTEGER         NOT NULL,
    start_date      DATE            NOT NULL,
    end_date        DATE            NOT NULL,
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_menu_discount_menu FOREIGN KEY (menu_id) REFERENCES menu (menu_id),
    CONSTRAINT chk_discount_date     CHECK (end_date >= start_date)
);

-- 매장별 메뉴 재고
CREATE TABLE stock (
    stock_id        BIGSERIAL       PRIMARY KEY,
    store_id        BIGINT          NOT NULL,
    menu_id         BIGINT          NOT NULL,
    quantity        INTEGER         NOT NULL DEFAULT 0,
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_stock_store FOREIGN KEY (store_id) REFERENCES store (store_id),
    CONSTRAINT fk_stock_menu  FOREIGN KEY (menu_id)  REFERENCES menu  (menu_id),
    CONSTRAINT uq_stock       UNIQUE (store_id, menu_id),
    CONSTRAINT chk_stock_qty  CHECK (quantity >= 0)
);

-- 주문
CREATE TABLE orders (
    order_id            BIGSERIAL       PRIMARY KEY,
    member_id           BIGINT          NOT NULL,
    store_id            BIGINT          NOT NULL,
    status              VARCHAR(20)     NOT NULL,   -- PENDING, ACCEPTED, PREPARING, COMPLETED, CANCELLED
    total_price         INTEGER         NOT NULL,
    order_type          VARCHAR(20)     NOT NULL,   -- TAKEOUT, DINE_IN
    customer_request    VARCHAR(500),
    created_by          BIGINT,
    created_at          TIMESTAMP,
    updated_by          BIGINT,
    updated_at          TIMESTAMP,

    CONSTRAINT fk_orders_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_orders_store  FOREIGN KEY (store_id)  REFERENCES store  (store_id)
);

-- 주문 항목
CREATE TABLE order_item (
    order_item_id   BIGSERIAL       PRIMARY KEY,
    order_id        BIGINT          NOT NULL,
    menu_id         BIGINT          NOT NULL,
    quantity        INTEGER         NOT NULL,
    unit_price      INTEGER         NOT NULL,
    options         JSONB,
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_order_item_menu  FOREIGN KEY (menu_id)  REFERENCES menu   (menu_id),
    CONSTRAINT chk_order_item_qty  CHECK (quantity > 0)
);

-- 주문 상태 변경 이력
CREATE TABLE order_status_history (
    history_id      BIGSERIAL       PRIMARY KEY,
    order_id        BIGINT          NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    changed_by      BIGINT,
    changed_at      TIMESTAMP       NOT NULL,

    CONSTRAINT fk_order_history_order  FOREIGN KEY (order_id)   REFERENCES orders (order_id),
    CONSTRAINT fk_order_history_member FOREIGN KEY (changed_by) REFERENCES member (member_id)
);

-- 결제
CREATE TABLE payment (
    payment_id      BIGSERIAL       PRIMARY KEY,
    order_id        BIGINT          NOT NULL UNIQUE,
    member_id       BIGINT          NOT NULL,
    amount          INTEGER         NOT NULL,
    method          VARCHAR(30)     NOT NULL,   -- CREDIT_CARD, KAKAO_PAY, NAVER_PAY
    status          VARCHAR(20)     NOT NULL,   -- PENDING, SUCCESS, FAILED, CANCELLED
    external_tx_id  VARCHAR(255),
    failed_reason   TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_payment_order  FOREIGN KEY (order_id)  REFERENCES orders  (order_id),
    CONSTRAINT fk_payment_member FOREIGN KEY (member_id) REFERENCES member (member_id)
);

-- 결제 이력
CREATE TABLE payment_history (
    payment_history_id  BIGSERIAL   PRIMARY KEY,
    payment_id          BIGINT      NOT NULL,
    status              VARCHAR(20) NOT NULL,   -- PENDING, SUCCESS, FAILED, CANCELLED
    reason              TEXT,
    changed_at          TIMESTAMP   NOT NULL,

    CONSTRAINT fk_payment_history FOREIGN KEY (payment_id) REFERENCES payment (payment_id)
);

-- ====================================
-- 인덱스
-- ====================================

CREATE INDEX idx_store_owner         ON store              (owner_id);
CREATE INDEX idx_stock_store_menu    ON stock              (store_id, menu_id);
CREATE INDEX idx_orders_member       ON orders             (member_id);
CREATE INDEX idx_orders_store        ON orders             (store_id);
CREATE INDEX idx_orders_status       ON orders             (status);
CREATE INDEX idx_order_item_order    ON order_item         (order_id);
CREATE INDEX idx_order_history_order ON order_status_history (order_id);
CREATE INDEX idx_payment_order       ON payment            (order_id);
CREATE INDEX idx_payment_history     ON payment_history    (payment_id);
CREATE INDEX idx_menu_discount_menu  ON menu_discount      (menu_id);
CREATE INDEX idx_menu_discount_date  ON menu_discount      (start_date, end_date);
