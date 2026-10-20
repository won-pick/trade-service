package com.wonpick.trade.repository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.wonpick.trade.domain.Trade;
import com.wonpick.trade.domain.TradeRole;
import com.wonpick.trade.domain.TradeStatus;

/**
 * trades / trade_status_histories 테이블.
 *
 * 상태 변경은 모두 "WHERE status = 지금 상태" 를 붙인 조건부 UPDATE 한 문장이다.
 * 확인과 변경이 한 덩어리라, 두 요청이 동시에 와도 한 번만 바뀐다. 반환값(바뀐 행 수)이 0이면 다른 요청이 먼저 바꾼 것.
 */
@Repository
public class TradeRepository {

    private static final String SELECT_ALL = """
            SELECT trade_id, product_id, product_title, price, buyer_id, seller_id, status,
                   payment_id, transfer_url, chat_room_id, cancel_reason, canceled_by, created_at, updated_at
              FROM trades""";

    private static final RowMapper<Trade> MAPPER = (rs, rowNum) -> new Trade(
            rs.getLong("trade_id"),
            rs.getLong("product_id"),
            rs.getString("product_title"),
            rs.getLong("price"),
            rs.getLong("buyer_id"),
            rs.getLong("seller_id"),
            TradeStatus.valueOf(rs.getString("status")),
            rs.getObject("payment_id", Long.class),
            rs.getString("transfer_url"),
            rs.getObject("chat_room_id", Long.class),
            rs.getString("cancel_reason"),
            rs.getObject("canceled_by", Long.class),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));

    private final JdbcClient jdbc;

    public TradeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long productId, String productTitle, long price, long buyerId, long sellerId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO trades (product_id, product_title, price, buyer_id, seller_id, status)
                        VALUES (:productId, :title, :price, :buyerId, :sellerId, 'REQUESTED')""")
                .param("productId", productId)
                .param("title", productTitle)
                .param("price", price)
                .param("buyerId", buyerId)
                .param("sellerId", sellerId)
                .update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "생성된 trade_id가 없습니다").longValue();
    }

    public Optional<Trade> findById(long tradeId) {
        return jdbc.sql(SELECT_ALL + " WHERE trade_id = :id")
                .param("id", tradeId)
                .query(MAPPER)
                .optional();
    }

    /** 같은 구매자가 같은 상품에 이미 열어 둔(끝나지 않은) 거래 */
    public Optional<Trade> findOpenByProductAndBuyer(long productId, long buyerId) {
        return jdbc.sql(SELECT_ALL + " " + """
                         WHERE product_id = :productId AND buyer_id = :buyerId
                           AND status IN ('REQUESTED', 'PAYMENT_PENDING', 'PAID')
                         ORDER BY trade_id DESC LIMIT 1""")
                .param("productId", productId)
                .param("buyerId", buyerId)
                .query(MAPPER)
                .optional();
    }

    /** 같은 상품의 다른 문의(REQUESTED) 거래들 — 한 명과 거래가 끝나면 나머지를 닫는다 */
    public List<Trade> findOtherRequested(long productId, long exceptTradeId) {
        return jdbc.sql(SELECT_ALL + " WHERE product_id = :productId AND trade_id <> :exceptId AND status = 'REQUESTED'")
                .param("productId", productId)
                .param("exceptId", exceptTradeId)
                .query(MAPPER)
                .list();
    }

    /** 내가 구매자이거나 판매자인 거래 목록. role·status·productId는 있으면 거른다 */
    public List<Trade> findByViewer(long viewerId, TradeRole role, TradeStatus status, Long productId) {
        StringBuilder sql = new StringBuilder(SELECT_ALL).append(" WHERE ");
        Map<String, Object> params = new HashMap<>();
        params.put("viewer", viewerId);

        if (role == TradeRole.BUYER) {
            sql.append("buyer_id = :viewer");
        } else if (role == TradeRole.SELLER) {
            sql.append("seller_id = :viewer");
        } else {
            sql.append("(buyer_id = :viewer OR seller_id = :viewer)");
        }
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        }
        if (productId != null) {
            sql.append(" AND product_id = :productId");
            params.put("productId", productId);
        }
        sql.append(" ORDER BY trade_id DESC");

        return jdbc.sql(sql.toString()).params(params).query(MAPPER).list();
    }

    public void updateChatRoom(long tradeId, long chatRoomId) {
        jdbc.sql("UPDATE trades SET chat_room_id = :chatRoomId WHERE trade_id = :id")
                .param("chatRoomId", chatRoomId)
                .param("id", tradeId)
                .update();
    }

    /**
     * 이 거래가 상품을 차지한다 (active_product_id = product_id).
     * 같은 상품을 다른 거래가 이미 차지했으면 UNIQUE 제약 때문에 DuplicateKeyException.
     */
    public int claimProduct(long tradeId) {
        return jdbc.sql("""
                        UPDATE trades SET active_product_id = product_id
                         WHERE trade_id = :id AND status = 'REQUESTED'""")
                .param("id", tradeId)
                .update();
    }

    /** 송금 요청 생성에 실패했을 때 차지를 되돌린다 (보상) */
    public void releaseProduct(long tradeId) {
        jdbc.sql("UPDATE trades SET active_product_id = NULL WHERE trade_id = :id AND status = 'REQUESTED'")
                .param("id", tradeId)
                .update();
    }

    public int toPaymentPending(long tradeId, long paymentId, String transferUrl) {
        return jdbc.sql("""
                        UPDATE trades SET status = 'PAYMENT_PENDING', payment_id = :paymentId, transfer_url = :transferUrl
                         WHERE trade_id = :id AND status = 'REQUESTED'""")
                .param("paymentId", paymentId)
                .param("transferUrl", transferUrl)
                .param("id", tradeId)
                .update();
    }

    public int toPaid(long tradeId) {
        return jdbc.sql("UPDATE trades SET status = 'PAID' WHERE trade_id = :id AND status = 'PAYMENT_PENDING'")
                .param("id", tradeId)
                .update();
    }

    public int toCompleted(long tradeId) {
        return jdbc.sql("""
                        UPDATE trades SET status = 'COMPLETED', active_product_id = NULL
                         WHERE trade_id = :id AND status = 'PAID'""")
                .param("id", tradeId)
                .update();
    }

    public int toCanceled(long tradeId, String reason, long canceledBy) {
        return jdbc.sql("""
                        UPDATE trades SET status = 'CANCELED', active_product_id = NULL,
                                          cancel_reason = :reason, canceled_by = :canceledBy
                         WHERE trade_id = :id AND status IN ('REQUESTED', 'PAYMENT_PENDING')""")
                .param("reason", reason)
                .param("canceledBy", canceledBy)
                .param("id", tradeId)
                .update();
    }

    public void insertHistory(long tradeId, TradeStatus from, TradeStatus to, long changedBy) {
        jdbc.sql("""
                        INSERT INTO trade_status_histories (trade_id, from_status, to_status, changed_by)
                        VALUES (:tradeId, :fromStatus, :toStatus, :changedBy)""")
                .param("tradeId", tradeId)
                .param("fromStatus", from == null ? null : from.name())
                .param("toStatus", to.name())
                .param("changedBy", changedBy)
                .update();
    }
}
