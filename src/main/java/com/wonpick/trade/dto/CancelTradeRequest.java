package com.wonpick.trade.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record CancelTradeRequest(
        @Schema(description = "취소 사유 (선택)", example = "일정이 안 맞아요")
        @Size(max = 200, message = "취소 사유는 200자 이하로 입력해 주세요.")
        String reason) {
}
