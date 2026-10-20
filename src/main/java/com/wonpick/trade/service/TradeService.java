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

import com.wonpick.trade.client.ChatClient;
import com.wonpick.trade.client.MarketClient;
import com.wonpick.trade.client.MarketClient.MarketProduct;
import com.wonpick.trade.client.PaymentClient;
import com.wonpick.trade.client.PaymentClient.PaymentResponse;
import com.wonpick.trade.domain.Trade;
import com.wonpick.trade.domain.TradeRole;
import com.wonpick.trade.domain.TradeStatus;
import com.wonpick.trade.exception.ErrorCode;
import com.wonpick.trade.exception.TradeException;
import com.wonpick.trade.repository.IdempotencyKeyRepository;
import com.wonpick.trade.repository.TradeRepository;

/**
 * 거래 흐름의 조율자. 구매 문의 → 송금 요청(판매자) → 입금 확인(판매자) → 구매 확정(구매자) / 취소.
 *
 * 원칙
 *  - 다른 서버 호출은 DB 트랜잭션 밖에서 한다 (느린 호출이 커넥션을 붙잡지 않게).
 *  - 상태 변경은 조건부 UPDATE. 바뀐 행이 0이면 다시 읽어서 "이미 목표 상태면 200, 아니면 409".
 *  - 이미 목표 상태인 요청을 다시 보내면 아무 일도 하지 않고 지금 거래를 돌려준다 (멱등).
 */
@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    /** created=false 이면 새로 만들지 않고 기존 거래를 돌려준 것 */
    public record CreateResult(Trade trade, boolean created) {
    }

    private final TradeRepository trades;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final MarketClient market;
    private final PaymentClient payment;
    private final ChatClient chat;
    private final TransactionTemplate tx;

    public TradeService(TradeRepository trades, IdempotencyKeyRepository idempotencyKeys, MarketClient market,
            PaymentClient payment, ChatClient chat, PlatformTransactionManager transactionManager) {
        this.trades = trades;
        this.idempotencyKeys = idempotencyKeys;
        this.market = market;
        this.payment = payment;
        this.chat = chat;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ───────────── ① 구매 문의 ─────────────

    public CreateResult create(long buyerId, long productId, String idempotencyKey) {
        // 같은 키로 다시 왔다 = 재시도. 처음 결과를 그대로 돌려준다 (step-07)
        if (idempotencyKey != null) {
            Optional<Long> existing = idempotencyKeys.findTradeId(idempotencyKey);
            if (existing.isPresent()) {
                return new CreateResult(getTrade(existing.get()), true);
            }
        }

        MarketProduct product = market.getProduct(productId);
        if (!MarketClient.ON_SALE.equals(product.status())) {
            throw new TradeException(ErrorCode.PRODUCT_NOT_AVAILABLE, "판매중인 상품이 아닙니다.");
        }
        if (Objects.equals(product.sellerId(), buyerId)) {
            throw new TradeException(ErrorCode.SELF_PURCHASE, "본인이 올린 상품은 구매할 수 없습니다.");
        }

        // 같은 구매자의 중복 문의는 막고, 다른 구매자의 문의는 막지 않는다 (한 상품에 여러 명이 채팅)
        Optional<Trade> mine = trades.findOpenByProductAndBuyer(productId, buyerId);
        if (mine.isPresent()) {
            return new CreateResult(mine.get(), false);
        }

        Long tradeId;
        try {
            tradeId = tx.execute(status -> {
                long id = trades.insert(productId, product.title(), product.price(), buyerId, product.sellerId());
                trades.insertHistory(id, null, TradeStatus.REQUESTED, buyerId);
                if (idempotencyKey != null) {
                    idempotencyKeys.save(idempotencyKey, id);   // 같은 키가 동시에 오면 여기서 DB가 거절 → 롤백
                }
                return id;
            });
        } catch (DuplicateKeyException e) {
            long firstTradeId = idempotencyKeys.findTradeId(idempotencyKey).orElseThrow(() -> e);
            return new CreateResult(getTrade(firstTradeId), true);
        }

        Trade trade = getTrade(Objects.requireNonNull(tradeId));
        Long chatRoomId = chat.createRoom(trade);   // 실패해도 거래는 유지 (chat 링크만 빠진다)
        if (chatRoomId != null) {
            trades.updateChatRoom(trade.tradeId(), chatRoomId);
            trade = getTrade(trade.tradeId());
        }
        return new CreateResult(trade, true);
    }

    // ───────────── 조회 ─────────────

    public Trade get(long tradeId, long memberId) {
        Trade trade = getTrade(tradeId);
        requireParticipant(trade, memberId);
        return trade;
    }

    public List<Trade> list(long memberId, TradeRole role, TradeStatus status, Long productId) {
        return trades.findByViewer(memberId, role, status, productId);
    }

    // ───────────── ② 송금 요청 (판매자만) ─────────────

    public Trade requestPayment(long tradeId, long memberId) {
        Trade trade = getTrade(tradeId);
        requireRole(trade, memberId, TradeRole.SELLER, "송금 요청은 판매자만 보낼 수 있습니다.");
        if (trade.status() == TradeStatus.PAYMENT_PENDING) {
            return trade;
        }
        if (!trade.status().canRequestPayment()) {
            throw invalidStatus(trade, "송금 요청");
        }

        // 1) 이 거래가 상품을 차지 — 같은 상품에 송금 단계 거래는 하나뿐 (UNIQUE 제약)
        try {
            if (trades.claimProduct(tradeId) == 0) {
                return currentOrConflict(tradeId, TradeStatus.PAYMENT_PENDING, "송금 요청");
            }
        } catch (DuplicateKeyException e) {
            throw new TradeException(ErrorCode.PRODUCT_NOT_AVAILABLE, "다른 구매자에게 이미 송금 요청을 보낸 상품입니다.");
        }

        // 2) Payment에 송금 요청 생성. 실패하면 1)을 되돌린다 (보상)
        PaymentResponse created;
        try {
            created = payment.requestTransfer(trade);
        } catch (TradeException e) {
            trades.releaseProduct(tradeId);
            throw e;
        }

        // 3) PAYMENT_PENDING
        Integer updated = tx.execute(status -> {
            int n = trades.toPaymentPending(tradeId, created.paymentId(), created.transferUrl());
            if (n == 1) {
                trades.insertHistory(tradeId, TradeStatus.REQUESTED, TradeStatus.PAYMENT_PENDING, memberId);
            }
            return n;
        });
        if (updated == null || updated == 0) {
            return currentOrConflict(tradeId, TradeStatus.PAYMENT_PENDING, "송금 요청");
        }
        chat.sendSystemMessage(trade.chatRoomId(), "판매자가 송금 요청을 보냈어요. 거래 화면에서 송금해 주세요.");
        return getTrade(tradeId);
    }

    // ───────────── ③ 입금 확인 (판매자) ─────────────

    public Trade confirmPayment(long tradeId, long memberId) {
        Trade trade = getTrade(tradeId);
        requireRole(trade, memberId, TradeRole.SELLER, "입금 확인은 판매자만 할 수 있습니다.");
        if (trade.status() == TradeStatus.PAID) {
            return trade;
        }
        if (!trade.status().canConfirmPayment()) {
            throw invalidStatus(trade, "입금 확인");
        }

        payment.approve(trade.paymentId());

        Integer updated = tx.execute(status -> {
            int n = trades.toPaid(tradeId);
            if (n == 1) {
                trades.insertHistory(tradeId, TradeStatus.PAYMENT_PENDING, TradeStatus.PAID, memberId);
            }
            return n;
        });
        if (updated == null || updated == 0) {
            return currentOrConflict(tradeId, TradeStatus.PAID, "입금 확인");
        }
        chat.sendSystemMessage(trade.chatRoomId(), "판매자가 입금을 확인했어요. 물건을 받으면 구매 확정을 눌러 주세요.");
        return getTrade(tradeId);
    }

    // ───────────── ④ 구매 확정 (구매자) ─────────────

    public Trade complete(long tradeId, long memberId) {
        Trade trade = getTrade(tradeId);
        requireRole(trade, memberId, TradeRole.BUYER, "구매 확정은 구매자만 할 수 있습니다.");
        if (trade.status() == TradeStatus.COMPLETED) {
            return trade;
        }
        if (!trade.status().canComplete()) {
            throw invalidStatus(trade, "구매 확정");
        }

        // Market 호출이 실패하면 502로 끝나고 거래는 PAID로 남는다 → 다시 눌러도 안전
        market.markSold(trade.productId());

        Integer updated = tx.execute(status -> {
            int n = trades.toCompleted(tradeId);
            if (n == 1) {
                trades.insertHistory(tradeId, TradeStatus.PAID, TradeStatus.COMPLETED, memberId);
                // 같은 상품에 문의만 하고 있던 다른 구매자들의 거래는 닫는다
                for (Trade other : trades.findOtherRequested(trade.productId(), tradeId)) {
                    if (trades.toCanceled(other.tradeId(), "다른 구매자와 거래가 완료되었습니다.", trade.sellerId()) == 1) {
                        trades.insertHistory(other.tradeId(), TradeStatus.REQUESTED, TradeStatus.CANCELED,
                                trade.sellerId());
                    }
                }
            }
            return n;
        });
        if (updated == null || updated == 0) {
            return currentOrConflict(tradeId, TradeStatus.COMPLETED, "구매 확정");
        }
        return getTrade(tradeId);
    }

    // ───────────── 취소 (구매자·판매자, 입금 확인 전까지) ─────────────

    public Trade cancel(long tradeId, long memberId, String reason) {
        Trade trade = getTrade(tradeId);
        requireParticipant(trade, memberId);
        if (trade.status() == TradeStatus.CANCELED) {
            return trade;
        }
        if (!trade.status().isCancelable()) {
            throw new TradeException(ErrorCode.INVALID_STATUS,
                    trade.status() + " 상태의 거래는 취소할 수 없습니다. 입금이 확인된 뒤에는 취소할 수 없어요.");
        }

        // 송금 요청이 나가 있었다면 Payment 쪽도 취소. 실패하면 502, 거래는 그대로
        if (trade.paymentId() != null) {
            payment.cancel(trade.paymentId());
        }

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
