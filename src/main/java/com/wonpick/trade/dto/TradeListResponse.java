package com.wonpick.trade.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TradeListResponse(
        List<TradeResponse> trades,
        @JsonProperty("_links") Map<String, Link> links) {
}
