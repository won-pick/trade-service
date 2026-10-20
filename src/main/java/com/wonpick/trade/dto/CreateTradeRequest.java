package com.wonpick.trade.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 송금 요청(= 거래 생성). 판매자가 채팅방에서 보낸다. 요청자(판매자)는 X-Member-Id 헤더로 받는다.
 * 금액·판매자·상품 제목은 받지 않는다 — Trade가 Market에서 직접 가져온다 (화면에서 금액을 고쳐 보내는 위변조 방지).
 */
public record CreateTradeRequest(
        @Schema(description = "송금을 요청할 상품 번호 (Market 서버)", example = "7")
        @NotNull(message = "productId는 필수입니다.")
        @Positive(message = "productId는 1 이상이어야 합니다.")
        Long productId,

        @Schema(description = "송금을 요청받는 구매자 회원 번호 (채팅 상대)", example = "3")
        @NotNull(message = "buyerId는 필수입니다.")
        @Positive(message = "buyerId는 1 이상이어야 합니다.")
        Long buyerId,

        @Schema(description = "송금 요청을 보낸 채팅방 번호 (Chat 서버, 선택)", example = "5")
        @Positive(message = "chatRoomId는 1 이상이어야 합니다.")
        Long chatRoomId) {
}
