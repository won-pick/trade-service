package com.wonpick.trade.exception;

import org.springframework.http.HttpStatus;

/** 실패의 종류. React와 다른 서버는 응답의 code 필드만 보고 사유를 가른다. */
public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값 오류"),
    NOT_TRADE_PARTICIPANT(HttpStatus.FORBIDDEN, "거래 당사자 아님"),
    ROLE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "허용되지 않은 역할"),
    TRADE_NOT_FOUND(HttpStatus.NOT_FOUND, "거래 없음"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품 없음"),
    PRODUCT_NOT_AVAILABLE(HttpStatus.CONFLICT, "거래할 수 없는 상품"),
    INVALID_STATUS(HttpStatus.CONFLICT, "거래 상태 오류"),
    SELF_PURCHASE(HttpStatus.UNPROCESSABLE_CONTENT, "본인 상품 구매"),
    UPSTREAM_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "연동 서버 오류"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
