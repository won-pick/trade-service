package com.wonpick.trade.domain;

import java.time.LocalDateTime;

/** trades 테이블 한 행. 상태를 바꾸는 일은 Repository의 조건부 UPDATE가 맡는다. */
public record Trade(
        Long tradeId,
        Long productId,
        String productTitle,
        long price,
        Long buyerId,
        Long sellerId,
        TradeStatus status,
        Long paymentId,
        Long chatRoomId,
        String cancelReason,
        Long canceledBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public boolean isBuyer(long memberId) {
        return buyerId != null && buyerId == memberId;
    }

    public boolean isSeller(long memberId) {
        return sellerId != null && sellerId == memberId;
    }

    public boolean isParticipant(long memberId) {
        return isBuyer(memberId) || isSeller(memberId);
    }

    /** 이 거래에서 memberId의 역할. 당사자가 아니면 null */
    public TradeRole roleOf(long memberId) {
        if (isBuyer(memberId)) {
            return TradeRole.BUYER;
        }
        if (isSeller(memberId)) {
            return TradeRole.SELLER;
        }
        return null;
    }
}
