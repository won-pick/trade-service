package com.wonpick.trade.client;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wonpick.trade.config.UpstreamProperties;
import com.wonpick.trade.domain.Trade;
import com.wonpick.trade.exception.ErrorCode;
import com.wonpick.trade.exception.TradeException;

/**
 * Payment 서버 (가상 잔액 결제) 호출 — payment-service 저장소의 API를 따른다.
 *
 * | 언제              | Payment API                                         | Payment 상태 |
 * |-------------------|-----------------------------------------------------|--------------|
 * | 판매자 결제 요청   | POST /orders {sellerId, amount}                     | PENDING      |
 * | 구매자 결제하기    | POST /orders/{paymentId}/payments {buyerId, idempotencyKey} | COMPLETED (잔액 이동) |
 * | 취소 전 확인       | GET  /payments/{paymentId}                          | —            |
 *
 * 금액은 React가 아니라 Trade가 거래 생성 때 저장한 가격(스냅샷)을 넘긴다 — 화면에서 금액을 고쳐 보내는 위변조 방지.
 */
@Component
public class PaymentClient {

    public static final String COMPLETED = "COMPLETED";

    public record OrderCreateRequest(Long sellerId, long amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderResponse(Long orderId, String status) {
    }

    public record PayRequest(Long buyerId, String idempotencyKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentResponse(Long paymentId, String status) {
    }

    private final RestClient rest;

    public PaymentClient(UpstreamProperties properties) {
        this.rest = RestClients.create(properties.payment().baseUrl());
    }

    /** POST /orders — 판매자가 결제를 요청할 때 결제 요청서(PENDING)를 만든다. 돌려받은 orderId가 곧 paymentId */
    public long createOrder(Trade trade) {
        OrderResponse response = RestClients.call("Payment", () -> rest.post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OrderCreateRequest(trade.sellerId(), trade.price()))
                .retrieve()
                .body(OrderResponse.class));
        if (response == null || response.orderId() == null) {
            throw new TradeException(ErrorCode.UPSTREAM_UNAVAILABLE, "Payment 서버가 결제 요청 번호를 주지 않았습니다.");
        }
        return response.orderId();
    }

    /**
     * POST /orders/{id}/payments — 구매자 잔액에서 판매자 잔액으로 옮긴다.
     * 멱등 키를 거래 번호로 고정해, 응답을 못 받고 다시 눌러도 돈이 두 번 빠지지 않는다 (Payment가 처음 결과를 돌려줌).
     */
    public void pay(long paymentId, long buyerId, long tradeId) {
        try {
            RestClients.call("Payment", () -> rest.post()
                    .uri("/orders/{id}/payments", paymentId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PayRequest(buyerId, "trade-" + tradeId + "-pay"))
                    .retrieve()
                    .toBodilessEntity());
        } catch (TradeException e) {
            if (e.getCause() instanceof HttpClientErrorException.BadRequest) {
                throw new TradeException(ErrorCode.PAYMENT_REJECTED, "잔액이 부족하거나 사용할 수 없는 계좌라 결제하지 못했습니다.", e);
            }
            if (e.getCause() instanceof HttpClientErrorException.NotFound) {
                throw new TradeException(ErrorCode.PAYMENT_REJECTED, "구매자 또는 판매자의 Payment 계좌가 없습니다.", e);
            }
            throw e;
        }
    }

    /** GET /payments/{id} — 결제 상태 (PENDING / COMPLETED) */
    public String getStatus(long paymentId) {
        PaymentResponse response = RestClients.call("Payment", () -> rest.get()
                .uri("/payments/{id}", paymentId)
                .retrieve()
                .body(PaymentResponse.class));
        if (response == null || response.status() == null) {
            throw new TradeException(ErrorCode.UPSTREAM_UNAVAILABLE, "Payment 서버가 결제 상태를 주지 않았습니다.");
        }
        return response.status();
    }
}
