package com.wonpick.trade.service;

import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.wonpick.trade.client.*;
import com.wonpick.trade.domain.*;
import com.wonpick.trade.exception.*;
import com.wonpick.trade.repository.*;

/**
 * 판매자의 결제 요청 → 구매자의 가상 잔액 결제 → 구매 확정.
 * 현재 배포는 Trade 한 인스턴스다. 변경 요청을 직렬화하고 외부 HTTP는 DB 트랜잭션 밖에서 실행한다.
 * REQUESTED는 외부 요청 실패 후 동일 거래로 다시 시도할 수 있는 준비 상태다.
 */
@Service
public class TradeService {
    public record CreateResult(Trade trade, boolean created) {}
    private final TradeRepository trades;
    private final IdempotencyKeyRepository keys;
    private final MarketClient market;
    private final PaymentClient payment;
    private final ChatClient chat;
    private final TransactionTemplate tx;

    public TradeService(TradeRepository trades, IdempotencyKeyRepository keys, MarketClient market,
            PaymentClient payment, ChatClient chat, PlatformTransactionManager manager) {
        this.trades = trades; this.keys = keys; this.market = market;
        this.payment = payment; this.chat = chat; this.tx = new TransactionTemplate(manager);
    }

    public synchronized CreateResult create(long sellerId, long productId, long buyerId,
            long chatRoomId, String key) {
        if (key != null) {
            var previous = keys.findTradeId(key);
            if (previous.isPresent()) {
                Trade trade = getTrade(previous.get());
                if (!trade.isSeller(sellerId) || trade.productId() != productId
                        || trade.buyerId() != buyerId || !Objects.equals(trade.chatRoomId(), chatRoomId)) {
                    throw new TradeException(ErrorCode.INVALID_STATUS, "같은 멱등 키를 다른 요청에 사용할 수 없습니다.");
                }
                return new CreateResult(resume(trade, sellerId), false);
            }
        }
        var product = market.getProduct(productId);
        if (!Objects.equals(product.sellerId(), sellerId)) {
            throw new TradeException(ErrorCode.ROLE_NOT_ALLOWED, "결제 요청은 상품 판매자만 보낼 수 있습니다.");
        }
        if (sellerId == buyerId) throw new TradeException(ErrorCode.SELF_PURCHASE, "본인 상품은 구매할 수 없습니다.");
        chat.validateRoom(chatRoomId, productId, buyerId, sellerId);
        var existing = trades.findOpenByProductAndBuyer(productId, buyerId);
        if (existing.isPresent()) {
            Trade trade = existing.get();
            if (!Objects.equals(trade.chatRoomId(), chatRoomId)) {
                throw new TradeException(ErrorCode.INVALID_INPUT, "기존 거래의 채팅방과 다릅니다.");
            }
            if (key != null) keys.save(key, trade.tradeId());
            return new CreateResult(resume(trade, sellerId), false);
        }
        if (!MarketClient.ON_SALE.equals(product.status())) {
            throw new TradeException(ErrorCode.PRODUCT_NOT_AVAILABLE, "판매 중인 상품이 아닙니다.");
        }
        // Payment와 같은 규격: 무료 상품은 채팅으로 합의하며 유료 결제를 요청하지 않는다.
        if (product.price() <= 0) throw new TradeException(ErrorCode.INVALID_INPUT, "결제 금액은 0원보다 커야 합니다.");
        Long id = tx.execute(status -> {
            long tradeId = trades.insert(productId, product.title(), product.price(), buyerId, sellerId);
            trades.updateChatRoom(tradeId, chatRoomId);
            trades.insertHistory(tradeId, null, TradeStatus.REQUESTED, sellerId);
            if (key != null) keys.save(key, tradeId);
            return tradeId;
        });
        return new CreateResult(requestPayment(Objects.requireNonNull(id), sellerId), true);
    }

    private Trade resume(Trade trade, long sellerId) {
        return trade.status() == TradeStatus.REQUESTED ? requestPayment(trade.tradeId(), sellerId) : trade;
    }
    public Trade get(long id, long memberId) {
        Trade trade = getTrade(id); requireParticipant(trade, memberId); return trade;
    }
    public List<Trade> list(long memberId, TradeRole role, TradeStatus status, Long productId, Long chatRoomId) {
        return trades.findByViewer(memberId, role, status, productId, chatRoomId);
    }

    public synchronized Trade requestPayment(long id, long memberId) {
        Trade trade = getTrade(id);
        requireRole(trade, memberId, TradeRole.SELLER);
        if (trade.status() == TradeStatus.PAYMENT_REQUESTED) return trade;
        if (!trade.status().canRequestPayment()) throw invalidStatus(trade);
        try {
            trades.claimProduct(id);
        } catch (DuplicateKeyException e) {
            throw new TradeException(ErrorCode.PRODUCT_NOT_AVAILABLE, "다른 구매자와 거래 중인 상품입니다.");
        }
        // 응답 유실 시 예약/주문을 되돌리지 않고 동일 tradeId/멱등 키로 재개한다.
        market.reserve(trade.productId(), id);
        long orderId = payment.createOrder(trade);
        tx.executeWithoutResult(status -> {
            if (trades.toPaymentPending(id, orderId) == 1)
                trades.insertHistory(id, TradeStatus.REQUESTED, TradeStatus.PAYMENT_REQUESTED, memberId);
        });
        return getTrade(id);
    }

    public synchronized Trade pay(long id, long memberId) {
        Trade trade = getTrade(id);
        requireRole(trade, memberId, TradeRole.BUYER);
        if (trade.status() == TradeStatus.PAID) return trade;
        if (!trade.status().canPay()) throw invalidStatus(trade);
        payment.pay(trade);
        tx.executeWithoutResult(status -> {
            if (trades.toPaid(id) == 1)
                trades.insertHistory(id, TradeStatus.PAYMENT_REQUESTED, TradeStatus.PAID, memberId);
        });
        return getTrade(id);
    }

    public synchronized Trade complete(long id, long memberId) {
        Trade trade = getTrade(id);
        requireRole(trade, memberId, TradeRole.BUYER);
        if (trade.status() == TradeStatus.COMPLETED) return trade;
        if (!trade.status().canComplete()) throw invalidStatus(trade);
        market.markSold(trade.productId(), id);
        tx.executeWithoutResult(status -> {
            if (trades.toCompleted(id) == 1)
                trades.insertHistory(id, TradeStatus.PAID, TradeStatus.COMPLETED, memberId);
        });
        return getTrade(id);
    }

    /** 구매 확정 전 취소. Payment의 실제 상태를 확인해 결제 완료 건은 환불한다. */
    public synchronized Trade cancel(long id, long memberId, String reason) {
        Trade trade = getTrade(id);
        requireParticipant(trade, memberId);
        if (trade.status() == TradeStatus.CANCELED) return trade;
        if (!trade.status().isCancelable()) throw invalidStatus(trade);
        // 주문 생성 응답을 받지 못한 REQUESTED도 같은 키로 주문을 찾아 취소한다.
        long orderId = trade.paymentId() == null ? payment.createOrder(trade) : trade.paymentId();
        payment.cancelOrRefund(orderId);
        market.release(trade.productId(), id);
        tx.executeWithoutResult(status -> {
            if (trades.toCanceled(id, reason, memberId) == 1)
                trades.insertHistory(id, trade.status(), TradeStatus.CANCELED, memberId);
        });
        return getTrade(id);
    }
    private Trade getTrade(long id) {
        return trades.findById(id).orElseThrow(() -> new TradeException(ErrorCode.TRADE_NOT_FOUND, "거래가 없습니다."));
    }
    private void requireParticipant(Trade trade, long memberId) {
        if (!trade.isParticipant(memberId))
            throw new TradeException(ErrorCode.NOT_TRADE_PARTICIPANT, "거래 당사자만 요청할 수 있습니다.");
    }
    private void requireRole(Trade trade, long memberId, TradeRole role) {
        requireParticipant(trade, memberId);
        if (trade.roleOf(memberId) != role)
            throw new TradeException(ErrorCode.ROLE_NOT_ALLOWED, "이 작업은 " + role + "만 할 수 있습니다.");
    }
    private TradeException invalidStatus(Trade trade) {
        return new TradeException(ErrorCode.INVALID_STATUS, trade.status() + " 상태에서 수행할 수 없는 요청입니다.");
    }
}
