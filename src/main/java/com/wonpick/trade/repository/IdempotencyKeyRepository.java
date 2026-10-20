package com.wonpick.trade.repository;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 멱등 키 저장소 (step-07) — "이 키로 들어온 구매 문의는 몇 번 거래가 되었나".
 * idem_key가 PK라서, 같은 키가 동시에 두 번 와도 두 번째 INSERT는 DB가 거절한다 (DuplicateKeyException).
 */
@Repository
public class IdempotencyKeyRepository {

    private final JdbcClient jdbc;

    public IdempotencyKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findTradeId(String key) {
        return jdbc.sql("SELECT trade_id FROM trade_idempotency_keys WHERE idem_key = :key")
                .param("key", key)
                .query(Long.class)
                .optional();
    }

    public void save(String key, long tradeId) {
        jdbc.sql("INSERT INTO trade_idempotency_keys (idem_key, trade_id) VALUES (:key, :tradeId)")
                .param("key", key)
                .param("tradeId", tradeId)
                .update();
    }
}
