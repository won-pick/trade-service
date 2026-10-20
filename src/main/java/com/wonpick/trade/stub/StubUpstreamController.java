package com.wonpick.trade.stub;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * [stub · stub-pay 프로필 전용] 다른 서버가 아직 없을 때 혼자 전체 흐름을 돌려 보기 위한 가짜 Market / Payment.
 * (Trade는 Chat을 부르지 않는다 — 채팅방은 화면이 Chat 서버에 직접 만든다.)
 * 실제 연동 때는 stub 프로필을 끄면 이 클래스는 아예 뜨지 않는다. Swagger에도 숨긴다.
 *
 * 가짜 데이터: 모든 상품의 판매자는 100번 회원, 가격은 상품번호 × 10,000원.
 */
@Hidden
@Profile({"stub", "stub-pay"})
@RestController
@RequestMapping("/stub")
public class StubUpstreamController {

    private static final long STUB_SELLER_ID = 100L;

    private final Map<Long, String> productStatus = new ConcurrentHashMap<>();
    private final Map<Long, Object> reservedBy = new ConcurrentHashMap<>();
    private final Map<Long, String> paymentStatus = new ConcurrentHashMap<>();
    private final AtomicLong paymentSequence = new AtomicLong();

    // ── Market (market-service 의 상품·예약 계약과 같은 모양) ──

    @GetMapping("/market/products/{id}")
    public Map<String, Object> product(@PathVariable("id") long id) {
        return Map.of(
                "productId", id,
                "sellerId", STUB_SELLER_ID,
                "sellerNickname", "판매자100",
                "title", "테스트 상품 " + id,
                "price", id * 10_000,
                "category", "ETC",
                "status", productStatus.getOrDefault(id, "ON_SALE"));
    }

    @PostMapping("/market/products/{id}/reservation")
    public Map<String, Object> reserve(@PathVariable("id") long id, @RequestBody Map<String, Object> request) {
        Object tradeId = request.get("tradeId");
        Object owner = reservedBy.putIfAbsent(id, tradeId);
        if (owner != null && !owner.equals(tradeId) || "SOLD".equals(productStatus.get(id))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PRODUCT_NOT_AVAILABLE");
        }
        productStatus.put(id, "RESERVED");
        return Map.of("productId", id, "tradeId", tradeId, "status", "RESERVED");
    }

    @DeleteMapping("/market/products/{id}/reservation")
    public Map<String, Object> release(@PathVariable("id") long id, @RequestBody Map<String, Object> request) {
        if (request.get("tradeId").equals(reservedBy.get(id))) {
            reservedBy.remove(id);
            productStatus.put(id, "ON_SALE");
        }
        return Map.of("productId", id, "tradeId", request.get("tradeId"), "status", "ON_SALE");
    }

    @PostMapping("/market/products/{id}/complete")
    public Map<String, Object> complete(@PathVariable("id") long id, @RequestBody Map<String, Object> request) {
        productStatus.put(id, "SOLD");
        return Map.of("productId", id, "tradeId", request.get("tradeId"), "status", "SOLD");
    }

    // ── Payment (payment-service 의 주문·결제 계약과 같은 모양. 잔액은 항상 충분하다고 본다) ──

    @PostMapping("/payment/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> request) {
        long id = paymentSequence.incrementAndGet();
        paymentStatus.put(id, "PENDING");
        return Map.of("orderId", id, "sellerId", request.get("sellerId"), "amount", request.get("amount"),
                "status", "PENDING");
    }

    @PostMapping("/payment/orders/{id}/payments")
    public Map<String, Object> pay(@PathVariable("id") long id, @RequestBody Map<String, Object> request) {
        if (!paymentStatus.containsKey(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "결제 요청서를 찾을 수 없습니다.");
        }
        paymentStatus.put(id, "COMPLETED");
        return Map.of("paymentId", id, "buyerId", request.get("buyerId"), "status", "COMPLETED");
    }

    @GetMapping("/payment/payments/{id}")
    public Map<String, Object> payment(@PathVariable("id") long id) {
        String status = paymentStatus.get(id);
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "결제 내역을 찾을 수 없습니다.");
        }
        return Map.of("paymentId", id, "status", status);
    }
}
