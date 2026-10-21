package com.wonpick.trade.client;

import org.springframework.stereotype.Component;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wonpick.trade.config.UpstreamProperties;
import com.wonpick.trade.exception.ErrorCode;
import com.wonpick.trade.exception.TradeException;

/** Market 서버 (회원·상품) 호출 */
@Component
public class MarketClient {

    public static final String ON_SALE = "ON_SALE";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketProduct(Long productId, String title, Long price, Long sellerId, String status) {
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

    public record ReservationRequest(long tradeId) {}
    public void reserve(long productId, long tradeId) {
        changeReservation(HttpMethod.POST, productId, tradeId, "reservation");
    }
    public void release(long productId, long tradeId) {
        changeReservation(HttpMethod.DELETE, productId, tradeId, "reservation");
    }
    public void markSold(long productId, long tradeId) {
        changeReservation(HttpMethod.POST, productId, tradeId, "complete");
    }
    private void changeReservation(HttpMethod method, long productId, long tradeId, String action) {
        RestClients.call("Market", () -> rest.method(method)
                .uri("/products/{id}/{action}", productId, action)
                .contentType(MediaType.APPLICATION_JSON).body(new ReservationRequest(tradeId))
                .retrieve().toBodilessEntity());
    }
}
