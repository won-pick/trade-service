package com.wonpick.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 다른 서버 주소 (application.yml 의 servers.*).
 * HATEOAS 링크의 다른 서버 주소도 여기서 가져온다.
 */
@ConfigurationProperties(prefix = "servers")
public record UpstreamProperties(Server market, Server chat, Server payment) {

    public record Server(String baseUrl) {
    }
}
