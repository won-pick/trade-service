package com.wonpick.trade.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 구매 문의 요청. 금액·판매자는 받지 않는다 — Trade가 Market에서 직접 가져온다. */
public record CreateTradeRequest(
        @Schema(description = "문의할 상품 번호 (Market 서버)", example = "7")
        @NotNull(message = "productId는 필수입니다.")
        @Positive(message = "productId는 1 이상이어야 합니다.")
        Long productId) {
}
