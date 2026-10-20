package com.wonpick.trade.client;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wonpick.trade.config.UpstreamProperties;
import com.wonpick.trade.domain.Trade;
import com.wonpick.trade.exception.ErrorCode;
import com.wonpick.trade.exception.TradeException;

/**
 * Payment 서버 (결제·환불) 호출.
 * 금액은 React가 아니라 Trade가 거래 생성 때 저장한 가격(스냅샷)을 넘긴다 — 화면에서 금액을 고쳐 보내는 위변조 방지.
 */
@Component
public class PaymentClient {

    public static final String METHOD_KAKAOPAY_TRANSFER = "KAKAOPAY_TRANSFER";

    public record PaymentCreateRequest(Long tradeId, Long payerId, Long payeeId, long amount, String method) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentResponse(Long paymentId, String status, String transferUrl) {
    }

    private final RestClient rest;

    public PaymentClient(UpstreamProperties properties) {
        this.rest = RestClients.create(properties.payment().baseUrl());
    }

    /**
     * POST /payments — 판매자가 구매자에게 보내는 송금 요청(READY).
     * 거래 번호로 만든 Idempotency-Key를 보내, 재시도해도 송금 요청이 두 건 생기지 않게 한다.
     */
    public PaymentResponse requestTransfer(Trade trade) {
        PaymentResponse response = RestClients.call("Payment", () -> rest.post()
                .uri("/payments")
                .header("Idempotency-Key", "trade-" + trade.tradeId() + "-payment-request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PaymentCreateRequest(trade.tradeId(), trade.buyerId(), trade.sellerId(),
                        trade.price(), METHOD_KAKAOPAY_TRANSFER))
                .retrieve()
                .body(PaymentResponse.class));
        if (response == null || response.paymentId() == null) {
            throw new TradeException(ErrorCode.UPSTREAM_UNAVAILABLE, "Payment 서버가 송금 요청 번호를 주지 않았습니다.");
        }
        return response;
    }

    /** POST /payments/{id}/approve — 판매자 입금 확인 */
    public void approve(long paymentId) {
        RestClients.call("Payment", () -> rest.post()
                .uri("/payments/{id}/approve", paymentId)
                .retrieve()
                .toBodilessEntity());
    }

    /** POST /payments/{id}/cancel — 송금 요청 취소 */
    public void cancel(long paymentId) {
        RestClients.call("Payment", () -> rest.post()
                .uri("/payments/{id}/cancel", paymentId)
                .retrieve()
                .toBodilessEntity());
    }
}
