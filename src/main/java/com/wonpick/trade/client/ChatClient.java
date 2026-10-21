package com.wonpick.trade.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.wonpick.trade.config.UpstreamProperties;
import com.wonpick.trade.exception.ErrorCode;
import com.wonpick.trade.exception.TradeException;

/** 기존 채팅방의 상품과 참여자를 검증한다. 알림은 프론트가 전송한다. */
@Component
public class ChatClient {
    public record Room(Long id, Long productId, Long buyerId, Long sellerId) {}
    private final RestClient rest;
    public ChatClient(UpstreamProperties properties) {
        rest = RestClients.create(properties.chat().baseUrl());
    }
    public void validateRoom(long roomId, long productId, long buyerId, long sellerId) {
        Room room = RestClients.call("Chat", () -> rest.get()
                .uri("/chat/rooms/{id}?memberId={memberId}", roomId, sellerId)
                .retrieve().body(Room.class));
        if (room == null || !Long.valueOf(roomId).equals(room.id())
                || !Long.valueOf(productId).equals(room.productId())
                || !Long.valueOf(buyerId).equals(room.buyerId())
                || !Long.valueOf(sellerId).equals(room.sellerId())) {
            throw new TradeException(ErrorCode.INVALID_INPUT, "채팅방의 상품·구매자·판매자가 요청과 일치하지 않습니다.");
        }
    }
}
