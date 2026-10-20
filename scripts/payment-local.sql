-- Local-only setup for payment-service (it uses ddl-auto=validate and has no schema.sql yet).
-- Tables mirror payment-service entities Account / Payment.
-- Seeds a virtual account with 1,000,000 KRW for Market user ids 1..200 (no account API exists yet).

CREATE DATABASE IF NOT EXISTS payment_db;
USE payment_db;

CREATE TABLE IF NOT EXISTS accounts (
  account_id BIGINT       NOT NULL AUTO_INCREMENT,
  user_id    BIGINT       NOT NULL,
  account_no VARCHAR(30)  NOT NULL,
  balance    BIGINT       NOT NULL,
  status     VARCHAR(20)  NOT NULL,
  created_at DATETIME(6)  NOT NULL,
  PRIMARY KEY (account_id),
  UNIQUE KEY uk_accounts_user (user_id),
  UNIQUE KEY uk_accounts_no (account_no)
);

CREATE TABLE IF NOT EXISTS payments (
  payment_id      BIGINT       NOT NULL AUTO_INCREMENT,
  seller_id       BIGINT       NOT NULL,
  buyer_id        BIGINT       NULL,
  amount          BIGINT       NOT NULL,
  status          VARCHAR(255) NOT NULL,
  idempotency_key VARCHAR(255) NULL,
  created_at      DATETIME(6)  NOT NULL,
  paid_at         DATETIME(6)  NULL,
  PRIMARY KEY (payment_id),
  UNIQUE KEY uk_payments_idem (idempotency_key)
);

INSERT IGNORE INTO accounts (user_id, account_no, balance, status, created_at)
SELECT n, CONCAT('WON-', LPAD(n, 6, '0')), 1000000, 'ACTIVE', NOW(6)
  FROM (WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 200) SELECT n FROM seq) s;
