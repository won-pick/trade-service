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

    /**
     * 송금 요청 = 거래 생성. 처음부터 PAYMENT_REQUESTED이고 상품을 차지한다(active_product_id).
     * 같은 상품에 진행 중인 거래가 이미 있으면 UNIQUE 제약 때문에 DuplicateKeyException.
     */
    public long insertRequested(long productId, String productTitle, long price, long buyerId, long sellerId,
            Long chatRoomId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO trades (product_id, product_title, price, buyer_id, seller_id, status,
                                            chat_room_id, active_product_id)
                        VALUES (:productId, :title, :price, :buyerId, :sellerId, 'PAYMENT_REQUESTED',
                                :chatRoomId, :productId)""")
                .param("productId", productId)
                .param("title", productTitle)
                .param("price", price)
                .param("buyerId", buyerId)
                .param("sellerId", sellerId)
                .param("chatRoomId", chatRoomId)
                .update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "생성된 trade_id가 없습니다").longValue();
    }

    public Optional<Trade> findById(long tradeId) {
        return jdbc.sql(SELECT_ALL + " WHERE trade_id = :id")
                .param("id", tradeId)
                .query(MAPPER)
                .optional();
    }

    /** 내가 구매자이거나 판매자인 거래 목록. role·status·productId·chatRoomId는 있으면 거른다 */
    public List<Trade> findByViewer(long viewerId, TradeRole role, TradeStatus status, Long productId,
            Long chatRoomId) {
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
        if (chatRoomId != null) {
            sql.append(" AND chat_room_id = :chatRoomId");
            params.put("chatRoomId", chatRoomId);
        }
        sql.append(" ORDER BY trade_id DESC");

        return jdbc.sql(sql.toString()).params(params).query(MAPPER).list();
    }

    /** Payment가 만든 송금 요청을 거래에 붙인다 */
    public int attachPayment(long tradeId, long paymentId, String transferUrl) {
        return jdbc.sql("""
                        UPDATE trades SET payment_id = :paymentId, transfer_url = :transferUrl
                         WHERE trade_id = :id AND status = 'PAYMENT_REQUESTED'""")
                .param("paymentId", paymentId)
                .param("transferUrl", transferUrl)
                .param("id", tradeId)
                .update();
    }

    public int toPaid(long tradeId) {
        return jdbc.sql("UPDATE trades SET status = 'PAID' WHERE trade_id = :id AND status = 'PAYMENT_REQUESTED'")
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
                         WHERE trade_id = :id AND status = 'PAYMENT_REQUESTED'""")
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
