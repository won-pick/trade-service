package com.wonpick.trade.client;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wonpick.trade.config.UpstreamProperties;
import com.wonpick.trade.exception.ErrorCode;
import com.wonpick.trade.exception.TradeException;

/**
 * Market 서버 (회원·상품) 호출 — market-service 저장소의 "판매 상태와 Trade 계약"을 따른다.
 *
 * | Market 상품 상태 | Trade가 부르는 API                          | 다음 상태 |
 * |-----------------|--------------------------------------------|----------|
 * | ON_SALE         | POST   /products/{id}/reservation {tradeId} | RESERVED |
 * | RESERVED        | DELETE /products/{id}/reservation {tradeId} | ON_SALE  |
 * | RESERVED        | POST   /products/{id}/complete    {tradeId} | SOLD     |
 *
 * 같은 tradeId로 다시 보내면 Market이 처음 결과를 돌려준다 (재시도 안전).
 */
@Component
public class MarketClient {

    public static final String ON_SALE = "ON_SALE";
    public static final String RESERVED = "RESERVED";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketProduct(Long productId, String title, Long price, Long sellerId, String status) {
    }

    public record ReservationRequest(Long tradeId) {
    }

    private final RestClient rest;

    public MarketClient(UpstreamProperties properties) {
        this.rest = RestClients.create(properties.market().baseUrl());
    }

    /** GET /products/{id} */
    public MarketProduct getProduct(long productId) {
        MarketProduct product;
        try {
            product = RestClients.call("Market", () -> rest.get()
                    .uri("/products/{id}", productId)
                    .retrieve()
                    .body(MarketProduct.class));
        } catch (TradeException e) {
            if (e.getCause() instanceof HttpClientErrorException.NotFound) {
                throw new TradeException(ErrorCode.PRODUCT_NOT_FOUND, productId + "번 상품이 없습니다.");
            }
            throw e;
        }
        if (product == null || product.price() == null || product.sellerId() == null) {
            throw new TradeException(ErrorCode.UPSTREAM_UNAVAILABLE, "Market 서버의 상품 정보가 올바르지 않습니다.");
        }
        return product;
    }

    /** 판매자가 송금 요청을 보낼 때 — 상품을 이 거래로 예약 (다른 사람은 문의할 수 없게 된다) */
    public void reserve(long productId, long tradeId) {
        try {
            RestClients.call("Market", () -> rest.post()
                    .uri("/products/{id}/reservation", productId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ReservationRequest(tradeId))
                    .retrieve()
                    .toBodilessEntity());
        } catch (TradeException e) {
            if (e.getCause() instanceof HttpClientErrorException.Conflict) {
                throw new TradeException(ErrorCode.PRODUCT_NOT_AVAILABLE, "이미 다른 거래로 예약되었거나 판매 완료된 상품입니다.");
            }
            throw e;
        }
    }

    /** 송금 요청이 취소되었을 때 — 예약 해제 (DELETE 이지만 본문에 tradeId가 필요하다) */
    public void release(long productId, long tradeId) {
        RestClients.call("Market", () -> rest.method(HttpMethod.DELETE)
                .uri("/products/{id}/reservation", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ReservationRequest(tradeId))
                .retrieve()
                .toBodilessEntity());
    }

    /** 구매 확정 — 판매 완료 */
    public void complete(long productId, long tradeId) {
        RestClients.call("Market", () -> rest.post()
                .uri("/products/{id}/complete", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ReservationRequest(tradeId))
                .retrieve()
                .toBodilessEntity());
    }
}
