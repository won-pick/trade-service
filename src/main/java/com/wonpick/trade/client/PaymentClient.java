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
 * | 언제              | Payment API                                                 | Payment 상태 |
 * |-------------------|-------------------------------------------------------------|--------------|
 * | 판매자 결제 요청   | POST /orders {sellerId, amount}                             | PENDING      |
 * | 구매자 결제하기    | POST /orders/{paymentId}/payments {buyerId, idempotencyKey} | COMPLETED (잔액 이동) |
 * | 결제 전 취소       | POST /orders/{paymentId}/cancel                             | CANCELED     |
 * | 결제 후 취소       | POST /payments/{paymentId}/refund                           | REFUNDED (잔액 되돌림) |
 *
 * 금액은 React가 아니라 Trade가 거래 생성 때 저장한 가격(스냅샷)을 넘긴다 — 화면에서 금액을 고쳐 보내는 위변조 방지.
 */
@Component
public class PaymentClient {

    public record OrderCreateRequest(Long sellerId, long amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderResponse(Long orderId, String status) {
    }

    public record PayRequest(Long buyerId, String idempotencyKey) {
    }

    /** Payment 오류 응답 (server.error.include-message=always 라 message에 사유가 담긴다) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentError(String message) {
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
        rejectable(() -> rest.post()
                .uri("/orders/{id}/payments", paymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PayRequest(buyerId, "trade-" + tradeId + "-pay"))
                .retrieve()
                .toBodilessEntity(), "결제하지 못했습니다.");
    }

    /** POST /orders/{id}/cancel — 송금 전 결제 요청서 취소. 이미 송금이 끝났으면 409 → false (환불로 넘어가야 함) */
    public boolean cancelOrder(long paymentId) {
        try {
            RestClients.call("Payment", () -> rest.post()
                    .uri("/orders/{id}/cancel", paymentId)
                    .retrieve()
                    .toBodilessEntity());
            return true;
        } catch (TradeException e) {
            if (e.getCause() instanceof HttpClientErrorException.Conflict) {
                return false;
            }
            throw e;
        }
    }

    /** POST /payments/{id}/refund — 판매자 잔액에서 구매자 잔액으로 되돌린다. 이미 환불이면 Payment가 그대로 돌려준다 */
    public void refund(long paymentId) {
        rejectable(() -> rest.post()
                .uri("/payments/{id}/refund", paymentId)
                .retrieve()
                .toBodilessEntity(), "환불하지 못했습니다.");
    }

    /** Payment가 4xx로 거절하면 Payment가 준 사유로 422 PAYMENT_REJECTED. 5xx·연결 실패는 502 그대로 */
    private void rejectable(Runnable call, String fallbackMessage) {
        try {
            RestClients.call("Payment", () -> {
                call.run();
                return null;
            });
        } catch (TradeException e) {
            if (e.getCause() instanceof HttpClientErrorException clientError) {
                throw new TradeException(ErrorCode.PAYMENT_REJECTED, reasonOf(clientError, fallbackMessage), e);
            }
            throw e;
        }
    }

    private static String reasonOf(HttpClientErrorException e, String fallbackMessage) {
        try {
            PaymentError body = e.getResponseBodyAs(PaymentError.class);
            if (body != null && body.message() != null && !body.message().isBlank()) {
                return body.message();
            }
        } catch (RuntimeException ignored) {
            // 본문이 JSON이 아니면 기본 문장
        }
        return fallbackMessage;
    }
}
