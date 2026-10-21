package com.wonpick.trade;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 HTTP 클라이언트로 Market/Chat/Payment의 요청 경로·본문과 재시도를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TradeContractTest {
    static final Map<Long,String> products = new ConcurrentHashMap<>();
    static final Map<Long,String> orders = new ConcurrentHashMap<>();
    static final Map<String,Long> keys = new ConcurrentHashMap<>();
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final AtomicLong sequence = new AtomicLong();
    static volatile boolean loseOrderResponse, losePayResponse;
    static final JsonMapper json = JsonMapper.builder().build();
    static final HttpServer upstream = start();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                calls.add(method + " " + path + " " + body);
                Object result;
                int status = 200;
                try {
                    String[] parts = path.split("/");
                    if (path.startsWith("/market/products/")) {
                        long id = Long.parseLong(parts[3]);
                        if (method.equals("GET")) {
                            result = Map.of("productId", id, "title", "상품", "sellerId", 100,
                                    "price", 10000, "status", products.getOrDefault(id, "ON_SALE"));
                        } else {
                            Number tradeId = JsonPath.read(body, "$.tradeId");
                            assertThat(tradeId.longValue()).isPositive();
                            String state = method.equals("DELETE") ? "ON_SALE"
                                    : path.endsWith("/complete") ? "SOLD" : "RESERVED";
                            products.put(id, state);
                            result = Map.of("productId", id, "tradeId", tradeId, "status", state);
                        }
                    } else if (path.startsWith("/chat/chat/rooms/") && method.equals("GET")) {
                        assertThat(exchange.getRequestURI().getQuery()).isEqualTo("memberId=100");
                        long id = Long.parseLong(parts[4]);
                        result = Map.of("id", id, "productId", id, "buyerId", 3, "sellerId", 100);
                    } else if (path.equals("/payment/orders") && method.equals("POST")) {
                        assertThat((Number) JsonPath.read(body, "$.sellerId")).isEqualTo(100);
                        assertThat((Number) JsonPath.read(body, "$.amount")).isEqualTo(10000);
                        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                        assertThat(key).startsWith("trade-").endsWith("-order");
                        long id = keys.computeIfAbsent(key, ignored -> {
                            long next = sequence.incrementAndGet(); orders.put(next, "PENDING"); return next;
                        });
                        result = Map.of("orderId", id, "status", orders.get(id));
                        if (loseOrderResponse) { loseOrderResponse = false; status = 503; }
                    } else if (path.startsWith("/payment/orders/") && path.endsWith("/payments")) {
                        long id = Long.parseLong(parts[3]);
                        assertThat((Number) JsonPath.read(body, "$.buyerId")).isEqualTo(3);
                        assertThat((String) JsonPath.read(body, "$.idempotencyKey")).endsWith("-pay");
                        orders.put(id, "COMPLETED");
                        result = Map.of("paymentId", id, "status", "COMPLETED");
                        if (losePayResponse) { losePayResponse = false; status = 503; }
                    } else if (path.startsWith("/payment/orders/") && path.endsWith("/cancel")) {
                        long id = Long.parseLong(parts[3]); orders.put(id, "CANCELED");
                        result = Map.of("paymentId", id, "status", "CANCELED");
                    } else if (path.startsWith("/payment/payments/")) {
                        long id = Long.parseLong(parts[3]);
                        if (path.endsWith("/refund")) orders.put(id, "REFUNDED");
                        result = Map.of("paymentId", id, "status", orders.get(id));
                    } else {
                        throw new AssertionError("Unexpected route " + method + " " + path);
                    }
                } catch (Throwable error) {
                    result = Map.of("error", error.toString()); status = 500;
                }
                byte[] bytes = json.writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        registry.add("servers.market.base-url", () -> base + "/market");
        registry.add("servers.chat.base-url", () -> base + "/chat");
        registry.add("servers.payment.base-url", () -> base + "/payment");
        registry.add("trade.cors.allowed-origins", () -> "http://192.168.1.50:5173");
    }
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM trade_idempotency_keys");
        jdbc.update("DELETE FROM trade_status_histories");
        jdbc.update("DELETE FROM trades");
        products.clear(); orders.clear(); keys.clear(); calls.clear();
        loseOrderResponse = false; losePayResponse = false;
    }
    @AfterAll static void stop() { upstream.stop(0); }
    ResultActions create(long product, long room, long seller, String key) throws Exception {
        return mvc.perform(post("/trades").header("X-Member-Id", seller).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + product + ",\"buyerId\":3,\"chatRoomId\":" + room + "}"));
    }
    long createTrade(long product, String key) throws Exception {
        String body = create(product, product, 100, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_REQUESTED")).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.tradeId")).longValue();
    }
    ResultActions action(long id, String action, long member) throws Exception {
        return mvc.perform(post("/trades/{id}/{action}", id, action).header("X-Member-Id", member));
    }
    @Test void sellerRequestBuyerPaysAndCompletesWithMatchingLinks() throws Exception {
        long id = createTrade(7, "flow");
        create(7, 7, 100, "flow").andExpect(status().isOk()).andExpect(jsonPath("$.tradeId").value(id));
        mvc.perform(get("/trades/{id}", id).header("X-Member-Id", 3))
                .andExpect(jsonPath("$._links.pay.href").value("http://localhost/trades/" + id + "/pay"))
                .andExpect(jsonPath("$._links.chat.href").value(
                        "http://127.0.0.1:" + upstream.getAddress().getPort() + "/chat/chat/rooms/7?memberId=3"))
                .andExpect(jsonPath("$._links.transfer").doesNotExist());
        action(id, "pay", 100).andExpect(status().isForbidden());
        action(id, "pay", 3).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$._links.cancel").exists());
        action(id, "pay", 3).andExpect(status().isOk());
        action(id, "complete", 100).andExpect(status().isForbidden());
        action(id, "complete", 3).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        action(id, "complete", 3).andExpect(status().isOk());
        action(id, "cancel", 3).andExpect(status().isConflict());
        assertThat(products.get(7L)).isEqualTo("SOLD");
        assertThat(orders).hasSize(1);
        assertThat(calls.stream().filter(c -> c.startsWith("POST /payment/orders/") && c.contains("/payments "))).hasSize(1);
    }
    @Test void roomFilterAndRequestIdentityAreEnforced() throws Exception {
        long first = createTrade(7, "first"); createTrade(8, "second");
        mvc.perform(get("/trades?chatRoomId=7").header("X-Member-Id", 3))
                .andExpect(jsonPath("$.trades.length()").value(1))
                .andExpect(jsonPath("$.trades[0].tradeId").value(first));
        create(8, 8, 100, "first").andExpect(status().isConflict());
        create(9, 10, 100, "wrong-room").andExpect(status().isBadRequest());
        create(9, 9, 3, "wrong-role").andExpect(status().isForbidden());
        mvc.perform(post("/trades").header("X-Member-Id", 100).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":7}")).andExpect(status().isBadRequest());
    }
    @Test void failedOrderResponseCanResumeWithoutDuplicateOrders() throws Exception {
        loseOrderResponse = true;
        create(7, 7, 100, "retry").andExpect(status().isBadGateway());
        assertThat(orders).hasSize(1);
        create(7, 7, 100, "retry").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_REQUESTED"));
        assertThat(orders).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trades", Integer.class)).isEqualTo(1);
    }
    @Test void cancellationAfterLostPayResponseRefundsBeforeReleasingProduct() throws Exception {
        long id = createTrade(7, "refund");
        losePayResponse = true;
        action(id, "pay", 3).andExpect(status().isBadGateway());
        action(id, "cancel", 3).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        action(id, "cancel", 3).andExpect(status().isOk());
        assertThat(orders.values()).containsExactly("REFUNDED");
        assertThat(products.get(7L)).isEqualTo("ON_SALE");
        int refund = -1, release = -1;
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).contains("/refund ")) refund = i;
            if (calls.get(i).startsWith("DELETE ")) release = i;
        }
        assertThat(refund).isGreaterThanOrEqualTo(0).isLessThan(release);
    }
    @Test void paidCancellationRetainsRemoteRefundBehavior() throws Exception {
        long id = createTrade(7, "paid-refund");
        action(id, "pay", 3).andExpect(status().isOk());
        action(id, "cancel", 100).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        action(id, "cancel", 100).andExpect(status().isOk());
        assertThat(orders.values()).containsExactly("REFUNDED");
        assertThat(products.get(7L)).isEqualTo("ON_SALE");
    }
    @Test void pendingCancellationAndLanCors() throws Exception {
        long id = createTrade(7, "cancel");
        action(id, "cancel", 100).andExpect(status().isOk());
        assertThat(orders.values()).containsExactly("CANCELED");
        mvc.perform(options("/trades").header("Origin", "http://192.168.1.50:5173")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "X-Member-Id"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://192.168.1.50:5173"));
    }
}
