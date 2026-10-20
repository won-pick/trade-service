package com.wonpick.trade.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wonpick.trade.config.UpstreamProperties;
import com.wonpick.trade.domain.Trade;
import com.wonpick.trade.exception.TradeException;

/**
 * Chat 서버 (실시간 소통) 호출.
 * 채팅은 거래의 부가 기능이라, 실패해도 거래를 막지 않는다 (로그만 남기고 null / 무시).
 */
@Component
public class ChatClient {

    private static final Logger log = LoggerFactory.getLogger(ChatClient.class);

    public record ChatRoomRequest(Long tradeId, Long productId, Long buyerId, Long sellerId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatRoomResponse(Long chatRoomId) {
    }

    public record SystemMessageRequest(String text) {
    }

    private final RestClient rest;

    public ChatClient(UpstreamProperties properties) {
        this.rest = RestClients.create(properties.chat().baseUrl());
    }

    /** POST /chat-rooms — 이 거래(상품 × 구매자) 전용 채팅방. 실패하면 null */
    public Long createRoom(Trade trade) {
        try {
            ChatRoomResponse response = RestClients.call("Chat", () -> rest.post()
                    .uri("/chat-rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ChatRoomRequest(trade.tradeId(), trade.productId(), trade.buyerId(), trade.sellerId()))
                    .retrieve()
                    .body(ChatRoomResponse.class));
            return response == null ? null : response.chatRoomId();
        } catch (TradeException e) {
            log.warn("채팅방을 만들지 못했습니다. tradeId={} : {}", trade.tradeId(), e.getMessage());
            return null;
        }
    }

    /** POST /chat-rooms/{id}/system-messages — "송금 요청이 도착했어요" 같은 알림 (선택 기능) */
    public void sendSystemMessage(Long chatRoomId, String text) {
        if (chatRoomId == null) {
            return;
        }
        try {
            RestClients.call("Chat", () -> rest.post()
                    .uri("/chat-rooms/{id}/system-messages", chatRoomId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SystemMessageRequest(text))
                    .retrieve()
                    .toBodilessEntity());
        } catch (TradeException e) {
            log.warn("채팅 알림을 보내지 못했습니다. chatRoomId={} : {}", chatRoomId, e.getMessage());
        }
    }
}
