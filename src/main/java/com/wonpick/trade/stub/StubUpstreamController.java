package com.wonpick.trade.stub;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * [stub 프로필 전용] 다른 서버가 아직 없을 때 혼자 전체 흐름을 돌려 보기 위한 가짜 Market / Chat / Payment.
 * 실제 연동 때는 stub 프로필을 끄면 이 클래스는 아예 뜨지 않는다. Swagger에도 숨긴다.
 *
 * 가짜 데이터: 모든 상품의 판매자는 100번 회원, 가격은 상품번호 × 10,000원.
 */
@Hidden
@Profile("stub")
@RestController
@RequestMapping("/stub")
public class StubUpstreamController {

    private static final long STUB_SELLER_ID = 100L;

    private final Set<Long> soldProducts = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, Object>> paymentsByKey = new ConcurrentHashMap<>();
    private final AtomicLong paymentSequence = new AtomicLong();
    private final AtomicLong chatRoomSequence = new AtomicLong();

    // ── Market ──

    @GetMapping("/market/products/{id}")
    public Map<String, Object> product(@PathVariable("id") long id) {
        return Map.of(
                "productId", id,
                "title", "테스트 상품 " + id,
                "price", id * 10_000,
                "sellerId", STUB_SELLER_ID,
                "status", soldProducts.contains(id) ? "SOLD" : "ON_SALE");
    }

    @PostMapping("/market/products/{id}/sold")
    public Map<String, Object> sold(@PathVariable("id") long id) {
        soldProducts.add(id);
        return product(id);
    }

    // ── Chat ──

    @PostMapping("/chat/chat-rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createChatRoom(@RequestBody Map<String, Object> request) {
        return Map.of("chatRoomId", chatRoomSequence.incrementAndGet());
    }

    @PostMapping("/chat/chat-rooms/{id}/system-messages")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> systemMessage(@PathVariable("id") long id, @RequestBody Map<String, Object> request) {
        return Map.of("chatRoomId", id, "text", String.valueOf(request.get("text")));
    }

    // ── Payment ──

    @PostMapping("/payment/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> request) {
        String safeKey = key == null ? "no-key-" + paymentSequence.get() : key;
        return paymentsByKey.computeIfAbsent(safeKey, k -> Map.of(
                "paymentId", paymentSequence.incrementAndGet(),
                "status", "READY",
                "transferUrl", "https://qr.kakaopay.com/stub-seller-link"));
    }

    @PostMapping("/payment/payments/{id}/approve")
    public Map<String, Object> approve(@PathVariable("id") long id) {
        return Map.of("paymentId", id, "status", "APPROVED");
    }

    @PostMapping("/payment/payments/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable("id") long id) {
        return Map.of("paymentId", id, "status", "CANCELED");
    }
}
