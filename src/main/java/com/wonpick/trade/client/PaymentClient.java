package com.wonpick.trade.client;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import java.util.function.Supplier;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wonpick.trade.config.UpstreamProperties;
import com.wonpick.trade.domain.Trade;
import com.wonpick.trade.exception.ErrorCode;
import com.wonpick.trade.exception.TradeException;

/** Payment의 가상 잔액 결제 계약. 외부 PG를 호출하지 않는다. */
@Component
public class PaymentClient {
    public record OrderRequest(Long sellerId, long amount) {}
    public record PayRequest(Long buyerId, String idempotencyKey) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderResponse(Long orderId, String status) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentResponse(Long paymentId, String status) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentError(String message, String detail) {}
    private final RestClient rest;
    public PaymentClient(UpstreamProperties properties) {
        rest = RestClients.create(properties.payment().baseUrl());
    }
    public long createOrder(Trade trade) {
        OrderResponse response = RestClients.call("Payment", () -> rest.post().uri("/orders")
                .header("Idempotency-Key", "trade-" + trade.tradeId() + "-order")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OrderRequest(trade.sellerId(), trade.price()))
                .retrieve().body(OrderResponse.class));
        if (response == null || response.orderId() == null) throw invalidResponse();
        return response.orderId();
    }
    public void pay(Trade trade) {
        PaymentResponse response = rejectable(() -> rest.post()
                .uri("/orders/{id}/payments", trade.paymentId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PayRequest(trade.buyerId(), "trade-" + trade.tradeId() + "-pay"))
                .retrieve().body(PaymentResponse.class));
        if (response == null || !trade.paymentId().equals(response.paymentId())
                || !"COMPLETED".equals(response.status())) throw invalidResponse();
    }
    /** 결제 응답 유실 후 취소해도 실제 Payment 상태에 맞춰 환불한다. */
    public void cancelOrRefund(long orderId) {
        PaymentResponse response = RestClients.call("Payment", () -> rest.get()
                .uri("/payments/{id}", orderId).retrieve().body(PaymentResponse.class));
        if (response == null || !Long.valueOf(orderId).equals(response.paymentId())) throw invalidResponse();
        switch (response.status()) {
            case "CANCELED", "REFUNDED" -> { return; }
            case "PENDING" -> RestClients.call("Payment", () -> rest.post()
                    .uri("/orders/{id}/cancel", orderId).retrieve().toBodilessEntity());
            case "COMPLETED" -> rejectable(() -> rest.post()
                    .uri("/payments/{id}/refund", orderId).retrieve().toBodilessEntity());
            default -> throw invalidResponse();
        }
    }
    private <T> T rejectable(Supplier<T> call) {
        try {
            return RestClients.call("Payment", call);
        } catch (TradeException e) {
            if (e.getCause() instanceof HttpClientErrorException error) {
                String reason = "결제 또는 환불이 거절되었습니다.";
                try {
                    PaymentError body = error.getResponseBodyAs(PaymentError.class);
                    if (body != null) {
                        if (body.message() != null && !body.message().isBlank()) reason = body.message();
                        else if (body.detail() != null && !body.detail().isBlank()) reason = body.detail();
                    }
                } catch (RuntimeException ignored) { }
                throw new TradeException(ErrorCode.PAYMENT_REJECTED, reason, e);
            }
            throw e;
        }
    }
    private TradeException invalidResponse() {
        return new TradeException(ErrorCode.UPSTREAM_UNAVAILABLE, "Payment 응답의 번호나 상태가 올바르지 않습니다.");
    }
}
