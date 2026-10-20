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
 * Chat 서버 (실시간 소통) 호출 — chat-service 저장소의 API를 따른다.
 *
 *  POST /chat/rooms                    {productId, buyerId, sellerId} → 201 새 방 / 200 기존 방, {id, ...}
 *  POST /chat/rooms/{roomId}/messages  Idempotency-Key + {senderId, content}
 *
 * 채팅은 거래의 부가 기능이라, 실패해도 거래를 막지 않는다 (로그만 남긴다).
 */
@Component
public class ChatClient {

    private static final Logger log = LoggerFactory.getLogger(ChatClient.class);

    public record ChatRoomRequest(Long productId, Long buyerId, Long sellerId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatRoomResponse(Long id) {
    }

    public record MessageRequest(Long senderId, String content) {
    }

    private final RestClient rest;

    public ChatClient(UpstreamProperties properties) {
        this.rest = RestClients.create(properties.chat().baseUrl());
    }

    /** 이 상품 × 구매자의 채팅방. 이미 있으면 Chat이 기존 방을 돌려준다. 실패하면 null */
    public Long openRoom(Trade trade) {
        try {
            ChatRoomResponse response = RestClients.call("Chat", () -> rest.post()
                    .uri("/chat/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ChatRoomRequest(trade.productId(), trade.buyerId(), trade.sellerId()))
                    .retrieve()
                    .body(ChatRoomResponse.class));
            return response == null ? null : response.id();
        } catch (TradeException e) {
            log.warn("채팅방을 만들지 못했습니다. tradeId={} : {}", trade.tradeId(), e.getMessage());
            return null;
        }
    }

    /**
     * 거래 알림 메시지. Chat에는 시스템 메시지가 없어서, 행동한 사람(senderId) 이름으로 보낸다.
     * 키를 거래 번호 + 행동으로 만들어서, 재시도해도 같은 메시지가 두 번 쌓이지 않는다.
     */
    public void notify(Trade trade, long senderId, String action, String content) {
        if (trade.chatRoomId() == null) {
            return;
        }
        try {
            RestClients.call("Chat", () -> rest.post()
                    .uri("/chat/rooms/{id}/messages", trade.chatRoomId())
                    .header("Idempotency-Key", "trade-" + trade.tradeId() + "-" + action)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new MessageRequest(senderId, content))
                    .retrieve()
                    .toBodilessEntity());
        } catch (TradeException e) {
            log.warn("채팅 알림을 보내지 못했습니다. tradeId={} : {}", trade.tradeId(), e.getMessage());
        }
    }
}
