-- WON-pick Trade 서버 스키마 (trade_db)
-- 다른 서버의 DB는 건드리지 않는다. 상품 제목·가격은 거래를 시작한 시점의 스냅샷으로 저장한다.

CREATE TABLE IF NOT EXISTS trades (
  trade_id          BIGINT       NOT NULL AUTO_INCREMENT,
  product_id        BIGINT       NOT NULL,
  product_title     VARCHAR(100) NOT NULL,          -- 스냅샷
  price             BIGINT       NOT NULL,          -- 원 단위, 스냅샷
  buyer_id          BIGINT       NOT NULL,
  seller_id         BIGINT       NOT NULL,
  status            VARCHAR(20)  NOT NULL,          -- REQUESTED / PAYMENT_REQUESTED / PAID / COMPLETED / CANCELED
  payment_id        BIGINT       NULL,
  chat_room_id      BIGINT       NULL,
  active_product_id BIGINT       NULL,              -- PAYMENT_REQUESTED·PAID일 때만 product_id, 그 외 NULL
  cancel_reason     VARCHAR(200) NULL,
  canceled_by       BIGINT       NULL,
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (trade_id),
  UNIQUE KEY uk_trades_active_product (active_product_id),   -- 한 상품에 송금 단계 거래는 하나뿐 (NULL은 여러 개 허용)
  INDEX idx_trades_buyer (buyer_id, status),
  INDEX idx_trades_seller (seller_id, status),
  INDEX idx_trades_product (product_id, status)
);

CREATE TABLE IF NOT EXISTS trade_idempotency_keys (
  idem_key   VARCHAR(64) NOT NULL,                   -- 같은 키의 두 번째 저장은 DB가 거절
  trade_id   BIGINT      NOT NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (idem_key)
);

CREATE TABLE IF NOT EXISTS trade_status_histories (
  history_id  BIGINT      NOT NULL AUTO_INCREMENT,
  trade_id    BIGINT      NOT NULL,
  from_status VARCHAR(20) NULL,
  to_status   VARCHAR(20) NOT NULL,
  changed_by  BIGINT      NOT NULL,
  changed_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (history_id),
  INDEX idx_histories_trade (trade_id)
);
