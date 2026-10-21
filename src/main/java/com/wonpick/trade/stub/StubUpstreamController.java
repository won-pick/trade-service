package com.wonpick.trade.stub;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;

/** stub 프로필의 규격 확인용 응답. 잔액 검증은 실제 Payment 서버에서 수행한다. */
@Hidden
@Profile({"stub", "stub-pay"})
@RestController
@RequestMapping("/stub")
public class StubUpstreamController {
    private final Map<Long, String> products = new ConcurrentHashMap<>();
    private final Map<String, Long> orderKeys = new ConcurrentHashMap<>();
    private final Map<Long, String> orders = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    @GetMapping("/market/products/{id}")
    public Map<String, Object> product(@PathVariable long id) {
        return Map.of("productId", id, "title", "테스트 상품 " + id, "price", id * 10000,
                "sellerId", 100, "status", products.getOrDefault(id, "ON_SALE"));
    }
    @PostMapping("/market/products/{id}/reservation")
    public Map<String, Object> reserve(@PathVariable long id, @RequestBody Map<String, Long> body) {
        products.put(id, "RESERVED");
        return Map.of("productId", id, "tradeId", body.get("tradeId"), "status", "RESERVED");
    }
    @DeleteMapping("/market/products/{id}/reservation")
    public Map<String, Object> release(@PathVariable long id, @RequestBody Map<String, Long> body) {
        products.put(id, "ON_SALE");
        return Map.of("productId", id, "tradeId", body.get("tradeId"), "status", "ON_SALE");
    }
    @PostMapping("/market/products/{id}/complete")
    public Map<String, Object> complete(@PathVariable long id, @RequestBody Map<String, Long> body) {
        products.put(id, "SOLD");
        return Map.of("productId", id, "tradeId", body.get("tradeId"), "status", "SOLD");
    }
    // 가짜 채팅방: roomId == productId, 판매자 100, 구매자 3
    @GetMapping("/chat/chat/rooms/{id}")
    public Map<String, Object> room(@PathVariable long id, @RequestParam long memberId) {
        return Map.of("id", id, "productId", id, "sellerId", 100, "buyerId", 3);
    }
    @PostMapping("/payment/orders")
    public Map<String, Object> order(@RequestHeader("Idempotency-Key") String key,
            @RequestBody Map<String, Object> body) {
        long id = orderKeys.computeIfAbsent(key, ignored -> {
            long next = sequence.incrementAndGet(); orders.put(next, "PENDING"); return next;
        });
        return Map.of("orderId", id, "status", orders.get(id));
    }
    @PostMapping("/payment/orders/{id}/payments")
    public Map<String, Object> pay(@PathVariable long id, @RequestBody Map<String, Object> body) {
        orders.put(id, "COMPLETED"); return payment(id);
    }
    @GetMapping("/payment/payments/{id}")
    public Map<String, Object> payment(@PathVariable long id) {
        return Map.of("paymentId", id, "status", orders.getOrDefault(id, "PENDING"));
    }
    @PostMapping("/payment/orders/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id) {
        orders.put(id, "CANCELED"); return payment(id);
    }
    @PostMapping("/payment/payments/{id}/refund")
    public Map<String, Object> refund(@PathVariable long id) {
        orders.put(id, "REFUNDED"); return payment(id);
    }
}
