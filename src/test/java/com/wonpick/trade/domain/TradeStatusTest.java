package com.wonpick.trade.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 상태 전이표가 코드와 같은지 확인한다 (DB·다른 서버 없이 실행) */
class TradeStatusTest {

    @Test
    void 결제는_PAYMENT_REQUESTED에서만() {
        assertThat(TradeStatus.PAYMENT_REQUESTED.canPay()).isTrue();
        assertThat(TradeStatus.PAID.canPay()).isFalse();
        assertThat(TradeStatus.CANCELED.canPay()).isFalse();
    }

    @Test
    void 구매_확정은_PAID에서만() {
        assertThat(TradeStatus.PAID.canComplete()).isTrue();
        assertThat(TradeStatus.PAYMENT_REQUESTED.canComplete()).isFalse();
    }

    @Test
    void 구매_확정_전까지_취소할_수_있다() {
        assertThat(TradeStatus.PAYMENT_REQUESTED.isCancelable()).isTrue();
        assertThat(TradeStatus.PAID.isCancelable()).isTrue();
        assertThat(TradeStatus.COMPLETED.isCancelable()).isFalse();
        assertThat(TradeStatus.CANCELED.isCancelable()).isFalse();
    }

    @Test
    void 판매자와_구매자_역할() {
        Trade trade = new Trade(1L, 7L, "아이패드", 250_000, 3L, 8L, TradeStatus.PAYMENT_REQUESTED,
                null, 5L, null, null, null, null);
        assertThat(trade.roleOf(3)).isEqualTo(TradeRole.BUYER);
        assertThat(trade.roleOf(8)).isEqualTo(TradeRole.SELLER);
        assertThat(trade.roleOf(99)).isNull();
    }
}
