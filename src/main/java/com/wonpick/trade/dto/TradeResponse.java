package com.wonpick.trade.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wonpick.trade.domain.TradeRole;
import com.wonpick.trade.domain.TradeStatus;

/**
 * 거래 응답 (step-04 DTO). 내부 컬럼(active_product_id 등)은 내보내지 않는다.
 * paymentId · chatRoomId · 송금 링크는 숫자 대신 _links 로 알려 준다.
 */
public record TradeResponse(
        Long tradeId,
        TradeStatus status,
        Long productId,
        String productTitle,
        long price,
        Long buyerId,
        Long sellerId,
        TradeRole myRole,
        String cancelReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        @JsonProperty("_links") Map<String, Link> links) {
}
