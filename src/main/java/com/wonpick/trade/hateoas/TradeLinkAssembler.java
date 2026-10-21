package com.wonpick.trade.hateoas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.wonpick.trade.config.UpstreamProperties;
import com.wonpick.trade.domain.Trade;
import com.wonpick.trade.domain.TradeStatus;
import com.wonpick.trade.dto.Link;
import com.wonpick.trade.dto.TradeListResponse;
import com.wonpick.trade.dto.TradeResponse;

/** 상태와 참여자 역할에 따라 실행 가능한 링크를 제공한다. */
@Component
public class TradeLinkAssembler {

    private final UpstreamProperties upstream;

    public TradeLinkAssembler(UpstreamProperties upstream) {
        this.upstream = upstream;
    }

    public TradeResponse toResponse(Trade trade, long viewerId) {
        return new TradeResponse(
                trade.tradeId(),
                trade.status(),
                trade.productId(),
                trade.productTitle(),
                trade.price(),
                trade.buyerId(),
                trade.sellerId(),
                trade.roleOf(viewerId),
                trade.cancelReason(),
                trade.createdAt(),
                trade.updatedAt(),
                linksOf(trade, viewerId));
    }

    public TradeListResponse toListResponse(List<Trade> trades, long viewerId) {
        Map<String, Link> links = new LinkedHashMap<>();
        links.put("self", new Link(ServletUriComponentsBuilder.fromCurrentRequest().toUriString()));
        return new TradeListResponse(trades.stream().map(t -> toResponse(t, viewerId)).toList(), links);
    }

    Map<String, Link> linksOf(Trade trade, long viewerId) {
        TradeStatus status = trade.status();
        boolean buyer = trade.isBuyer(viewerId);
        boolean seller = trade.isSeller(viewerId);
        String self = tradeUrl(trade.tradeId());

        Map<String, Link> links = new LinkedHashMap<>();
        links.put("self", new Link(self));
        // 다른 서버의 자원도 링크로 가리킨다
        links.put("product", new Link(upstream.market().baseUrl() + "/products/" + trade.productId()));
        if (trade.chatRoomId() != null && !status.isFinished()) {
            links.put("chat", new Link(upstream.chat().baseUrl() + "/chat/rooms/" + trade.chatRoomId() + "?memberId=" + viewerId));
        }
        if (trade.paymentId() != null && status != TradeStatus.REQUESTED && status != TradeStatus.CANCELED) {
            links.put("payment", new Link(upstream.payment().baseUrl() + "/payments/" + trade.paymentId()));
        }

        // 다음 행동 — 상태 전이 규칙(TradeStatus)과 같은 메서드로 정한다
        if (seller && status.canRequestPayment()) {
            links.put("request-payment", new Link(self + "/request-payment"));
        }
        if (buyer && status.canPay()) {
            links.put("pay", new Link(self + "/pay"));
        }
        if (buyer && status.canComplete()) {
            links.put("complete", new Link(self + "/complete"));
        }
        if ((buyer || seller) && status.isCancelable()) {
            links.put("cancel", new Link(self + "/cancel"));
        }
        return links;
    }

    private String tradeUrl(Long tradeId) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/trades/{id}")
                .buildAndExpand(tradeId)
                .toUriString();
    }
}
