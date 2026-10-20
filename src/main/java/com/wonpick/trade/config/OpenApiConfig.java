package com.wonpick.trade.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tradeOpenApi() {
        return new OpenAPI().info(new Info()
                .title("WON-pick Trade API")
                .version("v1")
                .description("""
                        거래(Trade) 서버 — 구매 문의 → 송금 요청(판매자) → 입금 확인(판매자) → 구매 확정(구매자).
                        모든 요청에 X-Member-Id 헤더(회원 번호)가 필요합니다.
                        응답의 _links 에 지금 할 수 있는 다음 행동이 담깁니다 (HATEOAS)."""));
    }
}
