package com.wonpick.trade.domain;

/**
 * 거래 상태와 상태 전이 규칙.
 *
 * 거래는 판매자가 채팅에서 "결제 요청"을 보내는 순간 PAYMENT_REQUESTED로 생긴다.
 * 문의·대화 단계는 Chat 서버의 채팅방이 맡으므로 Trade에는 없다.
 *
 * <pre>
 * (결제 요청, 판매자) ──▶ PAYMENT_REQUESTED ──pay(구매자)──▶ PAID ──complete(구매자)──▶ COMPLETED
 *                              │                            │
 *                              └──── cancel(구매자·판매자) ──┴──▶ CANCELED (PAID였으면 환불)
 * </pre>
 *
 * HATEOAS 링크도 이 메서드들로 정한다. 그래야 "버튼은 보이는데 누르면 409"가 생기지 않는다.
 */
public enum TradeStatus {
    PAYMENT_REQUESTED,
    PAID,
    COMPLETED,
    CANCELED;

    public boolean canPay() {
        return this == PAYMENT_REQUESTED;
    }

    public boolean canComplete() {
        return this == PAID;
    }

    /** 구매 확정 전까지 취소할 수 있다. 결제(PAID) 뒤의 취소는 Payment 환불로 잔액을 되돌린다. */
    public boolean isCancelable() {
        return this == PAYMENT_REQUESTED || this == PAID;
    }

    public boolean isFinished() {
        return this == COMPLETED || this == CANCELED;
    }
}
