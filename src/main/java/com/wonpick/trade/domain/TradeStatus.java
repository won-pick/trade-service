package com.wonpick.trade.domain;

/**
 * 거래 상태와 상태 전이 규칙.
 *
 * <pre>
 * REQUESTED ──request-payment(판매자)──▶ PAYMENT_PENDING ──confirm-payment(판매자)──▶ PAID ──complete(구매자)──▶ COMPLETED
 *     │                                        │
 *     └──────────── cancel(구매자·판매자) ──────┴──▶ CANCELED
 * </pre>
 *
 * HATEOAS 링크도 이 메서드들로 정한다. 그래야 "버튼은 보이는데 누르면 409"가 생기지 않는다.
 */
public enum TradeStatus {
    REQUESTED,
    PAYMENT_PENDING,
    PAID,
    COMPLETED,
    CANCELED;

    public boolean canRequestPayment() {
        return this == REQUESTED;
    }

    public boolean canConfirmPayment() {
        return this == PAYMENT_PENDING;
    }

    public boolean canComplete() {
        return this == PAID;
    }

    /** 입금이 확인(PAID)되기 전까지만 취소할 수 있다. 직송금이라 서버가 돈을 되돌릴 수 없기 때문. */
    public boolean isCancelable() {
        return this == REQUESTED || this == PAYMENT_PENDING;
    }

    public boolean isFinished() {
        return this == COMPLETED || this == CANCELED;
    }
}
