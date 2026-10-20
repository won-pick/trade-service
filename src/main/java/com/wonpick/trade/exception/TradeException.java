package com.wonpick.trade.exception;

/** Trade 서버의 업무 예외. 메시지는 그대로 응답 detail에 나가므로 사용자에게 보여 줄 문장만 넣는다. */
public class TradeException extends RuntimeException {

    private final ErrorCode errorCode;

    public TradeException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TradeException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
