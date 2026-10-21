package com.wonpick.trade.domain;

/**
 * 거래 상태와 상태 전이 규칙.
 *
 * <pre>
 * REQUESTED ──request-payment(판매자)──▶ PAYMENT_REQUESTED ──pay(구매자)──▶ PAID ──complete(구매자)──▶ COMPLETED
 *     │                                        │
 *     └──────────── cancel(구매자·판매자) ──────┴──▶ CANCELED
 * </pre>
 *
 * HATEOAS 링크도 이 메서드들로 정한다. 그래야 "버튼은 보이는데 누르면 409"가 생기지 않는다.
 */
public enum TradeStatus {
    REQUESTED,
    PAYMENT_REQUESTED,
    PAID,
    COMPLETED,
    CANCELED;

    public boolean canRequestPayment() {
        return this == REQUESTED;
    }

    public boolean canPay() {
        return this == PAYMENT_REQUESTED;
    }

    public boolean canComplete() {
        return this == PAID;
    }

    /** 구매 확정 전까지 취소할 수 있다. 결제 완료 건은 Payment 환불 후 예약을 해제한다. */
    public boolean isCancelable() {
        return this == REQUESTED || this == PAYMENT_REQUESTED || this == PAID;
    }

    public boolean isFinished() {
        return this == COMPLETED || this == CANCELED;
    }
}
