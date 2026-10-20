package com.wonpick.trade.client;

import org.springframework.stereotype.Component;
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

    /** POST /products/{id}/sold — 판매완료 (이미 판매완료여도 200을 기대) */
    public void markSold(long productId) {
        RestClients.call("Market", () -> rest.post()
                .uri("/products/{id}/sold", productId)
                .retrieve()
                .toBodilessEntity());
    }
}
