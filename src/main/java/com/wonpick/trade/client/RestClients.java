package com.wonpick.trade.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.wonpick.trade.exception.ErrorCode;
import com.wonpick.trade.exception.TradeException;

/**
 * 다른 서버를 부르는 RestClient 공통 설정.
 * 연결 2초 · 응답 3초를 넘기면 포기하고, 실패는 500이 아니라 502 UPSTREAM_UNAVAILABLE로 바꾼다.
 */
final class RestClients {

    private RestClients() {
    }

    static RestClient create(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /** 호출 하나를 감싸 실패를 TradeException(502)으로 바꾼다 */
    static <T> T call(String server, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            throw new TradeException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "%s 서버가 오류로 응답했습니다. (HTTP %d)".formatted(server, e.getStatusCode().value()), e);
        } catch (RestClientException e) {
            throw new TradeException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "%s 서버에 연결할 수 없습니다.".formatted(server), e);
        }
    }
}
