-- ====================================
-- WhaleOrder DDL
-- Database: PostgreSQL
-- ====================================

-- 회원
CREATE TABLE member (
    member_id       BIGSERIAL       PRIMARY KEY,
    user_id         VARCHAR(100)    UNIQUE,
    password        VARCHAR(255),
    name            VARCHAR(50)     NOT NULL,
    nickname        VARCHAR(50),
    phone           VARCHAR(20),
    provider        VARCHAR(20)     NOT NULL,   -- LOCAL, KAKAO
    provider_id     VARCHAR(100),
    role            VARCHAR(20)     NOT NULL,   -- CUSTOMER, OWNER, ADMIN
    is_deleted      BOOLEAN         NOT NULL DEFAULT false,
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
    phone           VARCHAR(20),
    open_time       TIME            NOT NULL,
    close_time      TIME            NOT NULL,
    status          VARCHAR(20)     NOT NULL,   -- OPEN, CLOSED
    latitude        DOUBLE PRECISION,
    longitude       DOUBLE PRECISION,
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_store_owner FOREIGN KEY (owner_id) REFERENCES member (member_id)
);

-- 메뉴
CREATE TABLE menu (
    menu_id         BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL,
    description     TEXT,
    base_price      BIGINT          NOT NULL,
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
    additional_price BIGINT         NOT NULL DEFAULT 0,
    is_required     BOOLEAN         NOT NULL DEFAULT false,
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
    version         BIGINT,
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_stock_store FOREIGN KEY (store_id) REFERENCES store (store_id),
    CONSTRAINT fk_stock_menu  FOREIGN KEY (menu_id)  REFERENCES menu  (menu_id),
    CONSTRAINT uq_stock       UNIQUE (store_id, menu_id),
    CONSTRAINT chk_stock_qty  CHECK (quantity >= -1)  -- -1 = 무제한 재고
);

-- 주문
CREATE TABLE orders (
    order_id            BIGSERIAL       PRIMARY KEY,
    version             BIGINT          NOT NULL DEFAULT 0,
    member_id           BIGINT          NOT NULL,
    store_id            BIGINT          NOT NULL,
    status              VARCHAR(20)     NOT NULL,   -- PENDING, PREPARING, COMPLETED, CANCELLED
    total_price         BIGINT          NOT NULL,
    order_type          VARCHAR(20)     NOT NULL,   -- TAKEOUT, DINE_IN
    customer_request    VARCHAR(500),
    stock_deducted      BOOLEAN         NOT NULL DEFAULT false,
    created_by          BIGINT,
    created_at          TIMESTAMP,
    updated_by          BIGINT,
    updated_at          TIMESTAMP,

    CONSTRAINT chk_orders_total_price CHECK (total_price >= 0),
    CONSTRAINT fk_orders_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_orders_store  FOREIGN KEY (store_id)  REFERENCES store  (store_id)
);

-- 주문 항목
CREATE TABLE order_item (
    order_item_id   BIGSERIAL       PRIMARY KEY,
    order_id        BIGINT          NOT NULL,
    menu_id         BIGINT          NOT NULL,
    quantity        INTEGER         NOT NULL,
    unit_price      BIGINT          NOT NULL,
    options         JSONB,
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_order_item_order  FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_order_item_menu   FOREIGN KEY (menu_id)  REFERENCES menu   (menu_id),
    CONSTRAINT chk_order_item_qty   CHECK (quantity > 0),
    CONSTRAINT chk_order_item_price CHECK (unit_price >= 0)
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
    amount          BIGINT          NOT NULL,
    method          VARCHAR(30)     NOT NULL,   -- CREDIT_CARD, KAKAO_PAY, NAVER_PAY
    status          VARCHAR(20)     NOT NULL,   -- PENDING, SUCCESS, FAILED, CANCELLED
    external_tx_id  VARCHAR(255),
    failed_reason   TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT chk_payment_amount CHECK (amount >= 0),
    CONSTRAINT fk_payment_order   FOREIGN KEY (order_id)  REFERENCES orders (order_id),
    CONSTRAINT fk_payment_member  FOREIGN KEY (member_id) REFERENCES member (member_id)
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

-- ====================================
-- 이벤트 / 굿즈 테이블
-- ====================================

-- 굿즈 (한정판 상품)
CREATE TABLE goods (
    goods_id        BIGSERIAL       PRIMARY KEY,
    store_id        BIGINT          NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    description     TEXT,
    price           BIGINT          NOT NULL,
    image_url       VARCHAR(500),
    created_by      BIGINT,
    created_at      TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_goods_store FOREIGN KEY (store_id) REFERENCES store (store_id)
);

-- 한정 판매 이벤트
CREATE TABLE event (
    event_id            BIGSERIAL       PRIMARY KEY,
    store_id            BIGINT          NOT NULL,
    goods_id            BIGINT          NOT NULL,
    name                VARCHAR(100)    NOT NULL,
    open_at             TIMESTAMP       NOT NULL,
    capacity            INTEGER         NOT NULL,
    per_person_limit    INTEGER         NOT NULL DEFAULT 1,
    status              VARCHAR(20)     NOT NULL,   -- SCHEDULED, OPEN, CLOSED
    remaining_capacity  INTEGER         NOT NULL,
    created_by          BIGINT,
    created_at          TIMESTAMP,
    updated_by          BIGINT,
    updated_at          TIMESTAMP,

    CONSTRAINT fk_event_store FOREIGN KEY (store_id) REFERENCES store  (store_id),
    CONSTRAINT fk_event_goods FOREIGN KEY (goods_id) REFERENCES goods  (goods_id),
    CONSTRAINT chk_event_capacity CHECK (remaining_capacity >= 0)
);

-- 이벤트 구매 이력
CREATE TABLE event_purchase (
    event_purchase_id   BIGSERIAL       PRIMARY KEY,
    event_id            BIGINT          NOT NULL,
    member_id           BIGINT          NOT NULL,
    created_by          BIGINT,
    created_at          TIMESTAMP,
    updated_by          BIGINT,
    updated_at          TIMESTAMP,

    CONSTRAINT fk_event_purchase_event  FOREIGN KEY (event_id)  REFERENCES event  (event_id),
    CONSTRAINT fk_event_purchase_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT uq_event_purchase        UNIQUE (event_id, member_id)
);

-- 재고 복원 실패 이력 (Saga 보상 트랜잭션 실패 감사)
CREATE TABLE stock_restore_failure (
    id          BIGSERIAL       PRIMARY KEY,
    order_id    BIGINT          NOT NULL,
    store_id    BIGINT          NOT NULL,
    menu_id     BIGINT          NOT NULL,
    quantity    INTEGER         NOT NULL,
    reason      TEXT            NOT NULL,
    failed_at   TIMESTAMP       NOT NULL
);

CREATE INDEX idx_event_store      ON event          (store_id);
CREATE INDEX idx_event_status     ON event          (status);
CREATE INDEX idx_event_purchase   ON event_purchase (event_id, member_id);

-- ====================================
-- 테이블 코멘트
-- ====================================

COMMENT ON TABLE member                IS '회원 테이블. 로컬 가입과 카카오 OAuth2 소셜 로그인을 통합 관리한다.';
COMMENT ON TABLE store                 IS '매장 테이블. 점주가 소유하는 카페 매장 정보를 관리한다.';
COMMENT ON TABLE menu                  IS '메뉴 테이블. 전체 브랜드 공통 메뉴이며 is_active로 소프트 삭제를 지원한다.';
COMMENT ON TABLE menu_option           IS '메뉴 옵션 테이블. 사이즈·온도·샷·시럽 등 커스터마이징 항목을 정의한다.';
COMMENT ON TABLE menu_discount         IS '메뉴 할인 테이블. 기간 한정 고정금액 할인을 관리한다.';
COMMENT ON TABLE stock                 IS '매장별 메뉴 재고 테이블. quantity = -1 이면 무제한 재고를 의미한다.';
COMMENT ON TABLE orders                IS '주문 테이블. stock_deducted 는 Kafka 컨슈머가 재고 차감을 완료했는지를 나타낸다.';
COMMENT ON TABLE order_item            IS '주문 항목 테이블. options 컬럼에 주문 시점 옵션 스냅샷을 JSONB로 저장한다.';
COMMENT ON TABLE order_status_history  IS '주문 상태 변경 이력 테이블. 상태 전이 감사 로그로 append-only 로 운영한다.';
COMMENT ON TABLE payment               IS '결제 테이블. 주문 1건에 결제 1건이 대응된다. Saga 보상 트랜잭션 지원.';
COMMENT ON TABLE payment_history       IS '결제 상태 변경 이력 테이블. 결제 상태 전이 감사 로그로 append-only 로 운영한다.';
COMMENT ON TABLE goods                 IS '굿즈(한정판 상품) 테이블. 매장별로 판매하는 텀블러·머그 등 굿즈 정보를 관리한다.';
COMMENT ON TABLE event                 IS '한정 판매 이벤트 테이블. 굿즈를 일정 수량만큼 특정 시각에 오픈하는 선착순 이벤트를 정의한다.';
COMMENT ON TABLE event_purchase        IS '이벤트 구매 이력 테이블. 회원 1인당 이벤트 1건 구매 제한을 UNIQUE 제약으로 보장한다.';
COMMENT ON TABLE stock_restore_failure IS '재고 복원 실패 이력 테이블. Saga 보상 트랜잭션 중 재고 복원에 실패한 케이스를 기록한다.';

-- ====================================
-- 컬럼 코멘트 - member
-- ====================================

COMMENT ON COLUMN member.member_id   IS '회원 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN member.user_id     IS '로컬 로그인 아이디. 소셜 로그인 회원은 NULL';
COMMENT ON COLUMN member.password    IS 'BCrypt 암호화 비밀번호. 소셜 로그인 회원은 NULL';
COMMENT ON COLUMN member.name        IS '실명';
COMMENT ON COLUMN member.nickname    IS '닉네임';
COMMENT ON COLUMN member.phone       IS '전화번호';
COMMENT ON COLUMN member.provider    IS '인증 제공자 (LOCAL | KAKAO)';
COMMENT ON COLUMN member.provider_id IS '소셜 로그인 제공자의 사용자 고유 ID. 로컬 회원은 NULL';
COMMENT ON COLUMN member.role        IS '시스템 역할 (CUSTOMER | OWNER | ADMIN)';
COMMENT ON COLUMN member.is_deleted  IS '소프트 삭제 여부. true 이면 로그인 불가 및 모든 조회에서 제외. FK 참조 데이터(주문, 결제 등) 보존을 위해 hard delete 대신 사용';

-- ====================================
-- 컬럼 코멘트 - store
-- ====================================

COMMENT ON COLUMN store.store_id       IS '매장 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN store.owner_id       IS '점주 회원 ID (FK → member)';
COMMENT ON COLUMN store.name           IS '매장명';
COMMENT ON COLUMN store.postal_code    IS '우편번호';
COMMENT ON COLUMN store.address        IS '기본 주소';
COMMENT ON COLUMN store.address_detail IS '상세 주소';
COMMENT ON COLUMN store.phone          IS '매장 대표 전화번호';
COMMENT ON COLUMN store.open_time      IS '영업 시작 시각';
COMMENT ON COLUMN store.close_time     IS '영업 종료 시각';
COMMENT ON COLUMN store.status         IS '영업 상태 (OPEN | CLOSED)';
COMMENT ON COLUMN store.latitude       IS '지도 API 연동용 위도';
COMMENT ON COLUMN store.longitude      IS '지도 API 연동용 경도';

-- ====================================
-- 컬럼 코멘트 - menu
-- ====================================

COMMENT ON COLUMN menu.menu_id         IS '메뉴 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN menu.name            IS '메뉴명';
COMMENT ON COLUMN menu.description     IS '메뉴 설명';
COMMENT ON COLUMN menu.base_price      IS '기본 가격 (원). 옵션 추가 요금은 별도';
COMMENT ON COLUMN menu.category        IS '메뉴 카테고리 (BEVERAGE | FOOD | DESSERT | DRINK)';
COMMENT ON COLUMN menu.image_url       IS '메뉴 이미지 URL';
COMMENT ON COLUMN menu.sale_start_date IS '판매 시작일. NULL 이면 제한 없음';
COMMENT ON COLUMN menu.sale_end_date   IS '판매 종료일. NULL 이면 제한 없음';
COMMENT ON COLUMN menu.is_active       IS '판매 활성 여부. false 이면 소프트 삭제 상태로 노출되지 않음';

-- ====================================
-- 컬럼 코멘트 - menu_option
-- ====================================

COMMENT ON COLUMN menu_option.menu_option_id   IS '메뉴 옵션 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN menu_option.menu_id          IS '소속 메뉴 ID (FK → menu)';
COMMENT ON COLUMN menu_option.option_group     IS '옵션 그룹 (SIZE | SHOT | SYRUP | TEMPERATURE)';
COMMENT ON COLUMN menu_option.option_name      IS '옵션 이름 (예: TALL, GRANDE, VENTI, HOT, ICED)';
COMMENT ON COLUMN menu_option.additional_price IS '해당 옵션 선택 시 추가 요금 (원). 기본값 0';
COMMENT ON COLUMN menu_option.is_required      IS '필수 선택 여부. 같은 menu_id+option_group 의 모든 행은 동일한 값을 유지해야 함. true 면 주문 시 해당 그룹에서 반드시 1개를 골라야 함';

-- ====================================
-- 컬럼 코멘트 - menu_discount
-- ====================================

COMMENT ON COLUMN menu_discount.discount_id     IS '할인 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN menu_discount.menu_id         IS '할인 대상 메뉴 ID (FK → menu)';
COMMENT ON COLUMN menu_discount.discount_amount IS '할인 금액 (원). 고정금액 차감 방식';
COMMENT ON COLUMN menu_discount.start_date      IS '할인 시작일';
COMMENT ON COLUMN menu_discount.end_date        IS '할인 종료일. start_date 이상이어야 함 (CHECK 제약)';

-- ====================================
-- 컬럼 코멘트 - stock
-- ====================================

COMMENT ON COLUMN stock.stock_id  IS '재고 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN stock.store_id  IS '매장 ID (FK → store)';
COMMENT ON COLUMN stock.menu_id   IS '메뉴 ID (FK → menu)';
COMMENT ON COLUMN stock.quantity  IS '현재 재고 수량. -1 이면 무제한 재고를 의미한다';

-- ====================================
-- 컬럼 코멘트 - orders
-- ====================================

COMMENT ON COLUMN orders.order_id         IS '주문 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN orders.version          IS 'JPA 낙관 락용 버전. cancelOrder와 Kafka Consumer 동시 실행 시 lost update 방지';
COMMENT ON COLUMN orders.member_id        IS '주문한 회원 ID (FK → member)';
COMMENT ON COLUMN orders.store_id         IS '주문 대상 매장 ID (FK → store)';
COMMENT ON COLUMN orders.status           IS '주문 상태 (PENDING | PREPARING | COMPLETED | CANCELLED)';
COMMENT ON COLUMN orders.total_price      IS '주문 총 금액 (원). 옵션 추가 요금 포함';
COMMENT ON COLUMN orders.order_type       IS '주문 유형 (TAKEOUT | DINE_IN)';
COMMENT ON COLUMN orders.customer_request IS '고객 특별 요청 사항';
COMMENT ON COLUMN orders.stock_deducted   IS 'Kafka 컨슈머가 재고 차감을 완료했으면 true. 중복 처리 방지용 플래그';

-- ====================================
-- 컬럼 코멘트 - order_item
-- ====================================

COMMENT ON COLUMN order_item.order_item_id IS '주문 항목 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN order_item.order_id      IS '소속 주문 ID (FK → orders)';
COMMENT ON COLUMN order_item.menu_id       IS '주문한 메뉴 ID (FK → menu)';
COMMENT ON COLUMN order_item.quantity      IS '주문 수량. 1 이상이어야 함 (CHECK 제약)';
COMMENT ON COLUMN order_item.unit_price    IS '주문 당시 단가 스냅샷 (원). 이후 메뉴 가격 변경에 영향받지 않음';
COMMENT ON COLUMN order_item.options       IS '주문 당시 선택 옵션 스냅샷 (JSONB). 예: [{"group":"SIZE","name":"GRANDE","additionalPrice":500}]';

-- ====================================
-- 컬럼 코멘트 - order_status_history
-- ====================================

COMMENT ON COLUMN order_status_history.history_id  IS '이력 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN order_status_history.order_id    IS '대상 주문 ID (FK → orders)';
COMMENT ON COLUMN order_status_history.status      IS '전이된 주문 상태 (PENDING | PREPARING | COMPLETED | CANCELLED)';
COMMENT ON COLUMN order_status_history.changed_by  IS '상태를 변경한 회원 ID. NULL 이면 시스템 자동 전이';
COMMENT ON COLUMN order_status_history.changed_at  IS '상태 변경 일시';

-- ====================================
-- 컬럼 코멘트 - payment
-- ====================================

COMMENT ON COLUMN payment.payment_id     IS '결제 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN payment.order_id       IS '결제 대상 주문 ID (FK → orders, UNIQUE). 주문 1건 = 결제 1건';
COMMENT ON COLUMN payment.member_id      IS '결제 회원 ID (FK → member)';
COMMENT ON COLUMN payment.amount         IS '결제 금액 (원)';
COMMENT ON COLUMN payment.method         IS '결제 수단 (CREDIT_CARD | KAKAO_PAY | NAVER_PAY)';
COMMENT ON COLUMN payment.status         IS '결제 상태 (PENDING | SUCCESS | FAILED | CANCELLED)';
COMMENT ON COLUMN payment.external_tx_id IS 'PG사 거래 ID. 환불·조회 등 외부 연동 시 사용';
COMMENT ON COLUMN payment.failed_reason  IS '결제 실패 사유. 실패 또는 취소 시에만 기록';

-- ====================================
-- 컬럼 코멘트 - payment_history
-- ====================================

COMMENT ON COLUMN payment_history.payment_history_id IS '결제 이력 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN payment_history.payment_id         IS '대상 결제 ID (FK → payment)';
COMMENT ON COLUMN payment_history.status             IS '전이된 결제 상태 (PENDING | SUCCESS | FAILED | CANCELLED)';
COMMENT ON COLUMN payment_history.reason             IS '상태 전이 사유. 실패 메시지 또는 취소 사유';
COMMENT ON COLUMN payment_history.changed_at         IS '결제 상태 변경 일시';

-- ====================================
-- 컬럼 코멘트 - goods
-- ====================================

COMMENT ON COLUMN goods.goods_id    IS '굿즈 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN goods.store_id    IS '굿즈를 판매하는 매장 ID (FK → store)';
COMMENT ON COLUMN goods.name        IS '굿즈 이름 (예: 시즌 텀블러, 벚꽃 머그)';
COMMENT ON COLUMN goods.description IS '굿즈 상세 설명';
COMMENT ON COLUMN goods.price       IS '굿즈 판매 가격 (원)';
COMMENT ON COLUMN goods.image_url   IS '굿즈 이미지 URL';

-- ====================================
-- 컬럼 코멘트 - event
-- ====================================

COMMENT ON COLUMN event.event_id            IS '이벤트 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN event.store_id            IS '이벤트를 진행하는 매장 ID (FK → store)';
COMMENT ON COLUMN event.goods_id            IS '이벤트에서 판매하는 굿즈 ID (FK → goods)';
COMMENT ON COLUMN event.name               IS '이벤트 이름';
COMMENT ON COLUMN event.open_at            IS '이벤트 판매 시작 일시 (선착순 오픈 시각)';
COMMENT ON COLUMN event.capacity           IS '총 판매 수량';
COMMENT ON COLUMN event.per_person_limit   IS '1인당 최대 구매 수량';
COMMENT ON COLUMN event.status             IS '이벤트 상태 (SCHEDULED | OPEN | CLOSED)';
COMMENT ON COLUMN event.remaining_capacity IS '현재 남은 수량. Redis 분산 락으로 동시성 제어';

-- ====================================
-- 컬럼 코멘트 - event_purchase
-- ====================================

COMMENT ON COLUMN event_purchase.event_purchase_id IS '이벤트 구매 이력 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN event_purchase.event_id          IS '구매한 이벤트 ID (FK → event)';
COMMENT ON COLUMN event_purchase.member_id         IS '구매한 회원 ID (FK → member). 이벤트당 1회만 구매 가능 (UNIQUE 제약)';

-- ====================================
-- 컬럼 코멘트 - stock_restore_failure
-- ====================================

COMMENT ON COLUMN stock_restore_failure.id        IS '실패 이력 고유 식별자 (PK, auto-increment)';
COMMENT ON COLUMN stock_restore_failure.order_id  IS '재고 복원이 실패한 주문 ID';
COMMENT ON COLUMN stock_restore_failure.store_id  IS '재고 복원 대상 매장 ID';
COMMENT ON COLUMN stock_restore_failure.menu_id   IS '재고 복원 대상 메뉴 ID';
COMMENT ON COLUMN stock_restore_failure.quantity  IS '복원 시도한 수량';
COMMENT ON COLUMN stock_restore_failure.reason    IS '복원 실패 사유 (예외 메시지)';
COMMENT ON COLUMN stock_restore_failure.failed_at IS '복원 실패 일시';
