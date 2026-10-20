package com.wonpick.trade.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.wonpick.trade.client.MarketClient;
import com.wonpick.trade.client.MarketClient.MarketProduct;
import com.wonpick.trade.client.PaymentClient;
import com.wonpick.trade.domain.Trade;
import com.wonpick.trade.domain.TradeRole;
import com.wonpick.trade.domain.TradeStatus;
import com.wonpick.trade.exception.ErrorCode;
import com.wonpick.trade.exception.TradeException;
import com.wonpick.trade.repository.IdempotencyKeyRepository;
import com.wonpick.trade.repository.TradeRepository;

/**
 * 거래 흐름의 조율자. 결제 요청(판매자, 거래 생성) → 결제(구매자, 잔액 이동) → 구매 확정(구매자) / 취소.
 * 문의·대화는 Chat 서버의 채팅방이 맡는다. 판매자가 채팅방에서 "결제 요청"을 누르면 화면이 Trade를 직접 부른다.
 *
 * 원칙
 *  - 다른 서버 호출은 DB 트랜잭션 밖에서 한다 (느린 호출이 커넥션을 붙잡지 않게).
 *  - 중간에 실패하면 앞에서 한 일을 거꾸로 되돌린다 (보상): Payment 실패 → Market 예약 해제 → 거래 취소.
 *  - 상태 변경은 조건부 UPDATE. 바뀐 행이 0이면 다시 읽어서 "이미 목표 상태면 200, 아니면 409".
 *  - 이미 목표 상태인 요청을 다시 보내면 아무 일도 하지 않고 지금 거래를 돌려준다 (멱등).
 */
@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    /** created=false 이면 같은 키의 재시도라 새로 만들지 않고 처음 거래를 돌려준 것 */
    public record CreateResult(Trade trade, boolean created) {
    }

    private final TradeRepository trades;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final MarketClient market;
    private final PaymentClient payment;
    private final TransactionTemplate tx;

    public TradeService(TradeRepository trades, IdempotencyKeyRepository idempotencyKeys, MarketClient market,
            PaymentClient payment, PlatformTransactionManager transactionManager) {
        this.trades = trades;
        this.idempotencyKeys = idempotencyKeys;
        this.market = market;
        this.payment = payment;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ───────────── ① 송금 요청 = 거래 생성 (판매자만) ─────────────

    public CreateResult requestPayment(long sellerId, long productId, long buyerId, Long chatRoomId,
            String idempotencyKey) {
        // 같은 키로 다시 왔다 = 재시도. 처음 결과를 그대로 돌려준다 (step-07)
        if (idempotencyKey != null) {
            Optional<Long> existing = idempotencyKeys.findTradeId(idempotencyKey);
            if (existing.isPresent()) {
                return new CreateResult(getTrade(existing.get()), false);
            }
        }

        // 1) Market 상품 확인 — 판매자 본인 상품이고, 판매 중이어야 한다
        MarketProduct product = market.getProduct(productId);
        if (!Objects.equals(product.sellerId(), sellerId)) {
            throw new TradeException(ErrorCode.ROLE_NOT_ALLOWED, "송금 요청은 상품을 올린 판매자만 보낼 수 있습니다.");
        }
        if (buyerId == sellerId) {
            throw new TradeException(ErrorCode.SELF_PURCHASE, "본인에게는 송금을 요청할 수 없습니다.");
        }
        if (MarketClient.RESERVED.equals(product.status())) {
            throw new TradeException(ErrorCode.PRODUCT_NOT_AVAILABLE, "다른 구매자와 거래가 진행 중인 상품입니다.");
        }
        if (!MarketClient.ON_SALE.equals(product.status())) {
            throw new TradeException(ErrorCode.PRODUCT_NOT_AVAILABLE, "판매가 끝난 상품입니다.");
        }

        // 2) 거래 저장 (PAYMENT_REQUESTED) — 같은 상품에 진행 중인 거래가 있으면 UNIQUE 제약이 막는다
        Long tradeId;
        try {
            tradeId = tx.execute(status -> {
                long id = trades.insertRequested(productId, product.title(), product.price(), buyerId, sellerId,
                        chatRoomId);
                trades.insertHistory(id, null, TradeStatus.PAYMENT_REQUESTED, sellerId);
                if (idempotencyKey != null) {
                    idempotencyKeys.save(idempotencyKey, id);
                }
                return id;
            });
        } catch (DuplicateKeyException e) {
            // 같은 키가 동시에 두 번 왔거나, 같은 상품에 이미 진행 중인 거래가 있다
            if (idempotencyKey != null) {
                Optional<Long> first = idempotencyKeys.findTradeId(idempotencyKey);
                if (first.isPresent()) {
                    return new CreateResult(getTrade(first.get()), false);
                }
            }
            throw new TradeException(ErrorCode.PRODUCT_NOT_AVAILABLE, "이 상품은 이미 다른 구매자와 거래가 진행 중입니다.");
        }
        Trade trade = getTrade(Objects.requireNonNull(tradeId));

        // 3) Market 상품 예약 (ON_SALE → RESERVED). 실패하면 거래를 취소로 남긴다 (보상)
        try {
            market.reserve(productId, trade.tradeId());
        } catch (TradeException e) {
            cancelAfterFailure(trade, sellerId, "상품 예약 실패");
            throw e;
        }

        // 4) Payment 결제 요청서 생성. 실패하면 3)·2)를 거꾸로 되돌린다 (보상)
        long paymentId;
        try {
            paymentId = payment.createOrder(trade);
        } catch (TradeException e) {
            releaseQuietly(trade);
            cancelAfterFailure(trade, sellerId, "결제 요청 생성 실패");
            throw e;
        }
        trades.attachPayment(trade.tradeId(), paymentId);
        return new CreateResult(getTrade(trade.tradeId()), true);
    }

    // ───────────── 조회 ─────────────

    public Trade get(long tradeId, long memberId) {
        Trade trade = getTrade(tradeId);
        requireParticipant(trade, memberId);
        return trade;
    }

    public List<Trade> list(long memberId, TradeRole role, TradeStatus status, Long productId, Long chatRoomId) {
        return trades.findByViewer(memberId, role, status, productId, chatRoomId);
    }

    // ───────────── ② 결제 (구매자, 잔액 이동) ─────────────

    public Trade pay(long tradeId, long memberId) {
        Trade trade = getTrade(tradeId);
        requireRole(trade, memberId, TradeRole.BUYER, "결제는 구매자만 할 수 있습니다.");
        if (trade.status() == TradeStatus.PAID) {
            return trade;
        }
        if (!trade.status().canPay() || trade.paymentId() == null) {
            throw invalidStatus(trade, "결제");
        }

        // 잔액 이동. 실패하면 거래는 PAYMENT_REQUESTED로 남는다 → 같은 멱등 키라 다시 눌러도 안전
        payment.pay(trade.paymentId(), memberId, tradeId);
        markPaid(tradeId, memberId);
        return getTrade(tradeId);
    }

    // ───────────── ③ 구매 확정 (구매자) ─────────────

    public Trade complete(long tradeId, long memberId) {
        Trade trade = getTrade(tradeId);
        requireRole(trade, memberId, TradeRole.BUYER, "구매 확정은 구매자만 할 수 있습니다.");
        if (trade.status() == TradeStatus.COMPLETED) {
            return trade;
        }
        if (!trade.status().canComplete()) {
            throw invalidStatus(trade, "구매 확정");
        }

        // Market 판매 완료 (RESERVED → SOLD). 실패하면 502로 끝나고 거래는 PAID로 남는다 → 다시 눌러도 안전
        market.complete(trade.productId(), tradeId);

        Integer updated = tx.execute(status -> {
            int n = trades.toCompleted(tradeId);
            if (n == 1) {
                trades.insertHistory(tradeId, TradeStatus.PAID, TradeStatus.COMPLETED, memberId);
            }
            return n;
        });
        if (updated == null || updated == 0) {
            return currentOrConflict(tradeId, TradeStatus.COMPLETED, "구매 확정");
        }
        return getTrade(tradeId);
    }

    // ───────────── 취소 (구매자·판매자, 구매 확정 전까지) ─────────────

    public Trade cancel(long tradeId, long memberId, String reason) {
        Trade trade = getTrade(tradeId);
        requireParticipant(trade, memberId);
        if (trade.status() == TradeStatus.CANCELED) {
            return trade;
        }
        if (!trade.status().isCancelable()) {
            throw new TradeException(ErrorCode.INVALID_STATUS,
                    trade.status() + " 상태의 거래는 취소할 수 없습니다. 구매 확정 뒤에는 취소할 수 없어요.");
        }

        // 1) Payment 정리. 결제 전이면 결제 요청서 취소 — 그 뒤로는 구매자가 결제할 수 없다.
        //    그 사이 결제가 끝났으면(취소가 409) 환불로 잔액을 되돌린다. 실패하면 거래는 그대로 (다시 눌러도 안전)
        if (trade.paymentId() != null) {
            boolean canceledBeforePay = trade.status() == TradeStatus.PAYMENT_REQUESTED
                    && payment.cancelOrder(trade.paymentId());
            if (!canceledBeforePay) {
                payment.refund(trade.paymentId());
            }
        }
        // 2) Market 예약 해제. 실패하면 502, 거래는 그대로 (다시 눌러도 안전)
        market.release(trade.productId(), tradeId);

        TradeStatus from = trade.status();
        Integer updated = tx.execute(status -> {
            int n = trades.toCanceled(tradeId, reason, memberId);
            if (n == 1) {
                trades.insertHistory(tradeId, from, TradeStatus.CANCELED, memberId);
            }
            return n;
        });
        if (updated == null || updated == 0) {
            return currentOrConflict(tradeId, TradeStatus.CANCELED, "취소");
        }
        log.info("거래 취소 tradeId={} by={} reason={}", tradeId, memberId, reason);
        return getTrade(tradeId);
    }

    // ───────────── 공통 ─────────────

    /** PAYMENT_REQUESTED → PAID. 이미 PAID면 그대로, 다른 상태로 바뀌어 있으면 409 */
    private void markPaid(long tradeId, long changedBy) {
        Integer updated = tx.execute(status -> {
            int n = trades.toPaid(tradeId);
            if (n == 1) {
                trades.insertHistory(tradeId, TradeStatus.PAYMENT_REQUESTED, TradeStatus.PAID, changedBy);
            }
            return n;
        });
        if (updated == null || updated == 0) {
            currentOrConflict(tradeId, TradeStatus.PAID, "결제");
        }
    }

    /** 송금 요청 도중 다른 서버가 실패했을 때 — 만들어 둔 거래를 취소로 남기고 상품 차지를 푼다 */
    private void cancelAfterFailure(Trade trade, long sellerId, String reason) {
        tx.executeWithoutResult(status -> {
            if (trades.toCanceled(trade.tradeId(), reason, sellerId) == 1) {
                trades.insertHistory(trade.tradeId(), TradeStatus.PAYMENT_REQUESTED, TradeStatus.CANCELED, sellerId);
            }
        });
    }

    /** 보상 중의 보상 — 이미 실패를 응답하는 중이라, 예약 해제 실패는 로그만 남긴다 */
    private void releaseQuietly(Trade trade) {
        try {
            market.release(trade.productId(), trade.tradeId());
        } catch (TradeException e) {
            log.error("Market 예약 해제 실패 — 수동 확인 필요. tradeId={} productId={}", trade.tradeId(), trade.productId(), e);
        }
    }

    private Trade getTrade(long tradeId) {
        return trades.findById(tradeId)
                .orElseThrow(() -> new TradeException(ErrorCode.TRADE_NOT_FOUND, tradeId + "번 거래가 없습니다."));
    }

    private void requireParticipant(Trade trade, long memberId) {
        if (!trade.isParticipant(memberId)) {
            throw new TradeException(ErrorCode.NOT_TRADE_PARTICIPANT, "이 거래의 구매자나 판매자만 볼 수 있습니다.");
        }
    }

    private void requireRole(Trade trade, long memberId, TradeRole role, String message) {
        requireParticipant(trade, memberId);
        if (trade.roleOf(memberId) != role) {
            throw new TradeException(ErrorCode.ROLE_NOT_ALLOWED, message);
        }
    }

    /** 조건부 UPDATE가 0행이었을 때 — 다른 요청이 먼저 바꿨다. 목표 상태면 그대로, 아니면 409 */
    private Trade currentOrConflict(long tradeId, TradeStatus target, String action) {
        Trade current = getTrade(tradeId);
        if (current.status() == target) {
            return current;
        }
        throw invalidStatus(current, action);
    }

    private TradeException invalidStatus(Trade trade, String action) {
        return new TradeException(ErrorCode.INVALID_STATUS,
                "%s 상태의 거래는 %s을(를) 할 수 없습니다.".formatted(trade.status(), action));
    }
}
