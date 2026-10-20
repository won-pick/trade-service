package com.wonpick.trade.exception;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 실패를 ProblemDetail(application/problem+json) 한 가지 모양 + code 필드로 바꾼다 (step-06).
 * 스프링이 던지는 오류(헤더 누락, 형식 오류, 405 ...)는 부모 클래스가 처리하고, 여기서 code만 덧붙인다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TradeException.class)
    public ResponseEntity<ProblemDetail> handleTrade(TradeException e) {
        ErrorCode code = e.getErrorCode();
        if (code == ErrorCode.UPSTREAM_UNAVAILABLE) {
            log.warn("연동 서버 오류: {}", e.getMessage(), e.getCause());
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), e.getMessage());
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        return ResponseEntity.status(code.status()).body(problem);
    }

    /** 그물 — 위에서 아무도 받지 않은 예외. 내부 메시지는 밖으로 내보내지 않는다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnknown(Exception e) {
        log.error("처리하지 못한 예외", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), "잠시 후 다시 시도해 주세요.");
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        return ResponseEntity.status(code.status()).body(problem);
    }

    /** @Valid 실패 — 틀린 필드를 모두 알려 준다 */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setTitle(ErrorCode.INVALID_INPUT.title());
        problem.setDetail("요청 값이 올바르지 않습니다.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** 스프링 기본 오류 응답에도 code를 붙인다 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            Map<String, Object> properties = problem.getProperties();
            if (properties == null || !properties.containsKey("code")) {
                problem.setProperty("code", codeFor(statusCode));
            }
        }
        return response;
    }

    private String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.INVALID_INPUT.name();
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "HTTP_" + status.value();
        };
    }
}
