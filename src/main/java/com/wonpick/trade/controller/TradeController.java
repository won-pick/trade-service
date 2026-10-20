package com.wonpick.trade.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wonpick.trade.domain.Trade;
import com.wonpick.trade.domain.TradeRole;
import com.wonpick.trade.domain.TradeStatus;
import com.wonpick.trade.dto.CancelTradeRequest;
import com.wonpick.trade.dto.CreateTradeRequest;
import com.wonpick.trade.dto.TradeListResponse;
import com.wonpick.trade.dto.TradeResponse;
import com.wonpick.trade.hateoas.TradeLinkAssembler;
import com.wonpick.trade.service.TradeService;
import com.wonpick.trade.service.TradeService.CreateResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 거래 자원 /trades. 상태를 바꾸는 요청은 행위 주소(/request-payment 등)에 POST.
 * 컨트롤러에는 try-catch가 없다 — 실패는 GlobalExceptionHandler가 ProblemDetail로 바꾼다.
 */
@Tag(name = "Trade", description = "거래: 구매 문의 → 송금 요청(판매자) → 입금 확인(판매자) → 구매 확정(구매자) / 취소")
@RestController
@RequestMapping("/trades")
public class TradeController {

    private static final String MEMBER_HEADER = "X-Member-Id";

    private final TradeService tradeService;
    private final TradeLinkAssembler assembler;

    public TradeController(TradeService tradeService, TradeLinkAssembler assembler) {
        this.tradeService = tradeService;
        this.assembler = assembler;
    }

    @Operation(summary = "구매 문의 (거래 생성)",
            description = "구매자가 상품에 문의를 연다. 한 상품에 여러 구매자가 각자 거래·채팅방을 가질 수 있다. "
                    + "같은 구매자가 이미 연 거래가 있으면 200 + 기존 거래, 새로 만들면 201 + Location.")
    @PostMapping
    public ResponseEntity<TradeResponse> create(
            @Parameter(description = "요청자 회원 번호 (구매자)") @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @Parameter(description = "재시도해도 거래가 두 건 생기지 않게 하는 키 (UUID 권장)")
            @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CreateTradeRequest request) {
        CreateResult result = tradeService.create(memberId, request.productId(), idempotencyKey);
        TradeResponse body = assembler.toResponse(result.trade(), memberId);
        if (result.created()) {
            return ResponseEntity.created(URI.create(body.links().get("self").href())).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "거래 조회", description = "구매자·판매자만 조회할 수 있다. _links 는 보는 사람의 역할에 따라 달라진다.")
    @GetMapping("/{tradeId}")
    public TradeResponse get(
            @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @PathVariable("tradeId") @Positive long tradeId) {
        return assembler.toResponse(tradeService.get(tradeId, memberId), memberId);
    }

    @Operation(summary = "내 거래 목록",
            description = "내가 구매자 또는 판매자인 거래. role·status·productId로 거른다. "
                    + "판매자가 productId로 조회하면 그 상품에 문의한 구매자별 거래 목록이 된다.")
    @GetMapping
    public TradeListResponse list(
            @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @RequestParam(name = "role", required = false) TradeRole role,
            @RequestParam(name = "status", required = false) TradeStatus status,
            @RequestParam(name = "productId", required = false) @Positive Long productId) {
        List<Trade> trades = tradeService.list(memberId, role, status, productId);
        return assembler.toListResponse(trades, memberId);
    }

    @Operation(summary = "송금 요청 보내기 (판매자만)",
            description = "판매자가 문의한 구매자 중 한 명에게 송금 요청을 보낸다. REQUESTED → PAYMENT_PENDING. "
                    + "구매자에게는 _links.transfer 로 판매자의 카카오페이 송금 링크가 보인다.")
    @PostMapping("/{tradeId}/request-payment")
    public TradeResponse requestPayment(
            @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @PathVariable("tradeId") @Positive long tradeId) {
        return assembler.toResponse(tradeService.requestPayment(tradeId, memberId), memberId);
    }

    @Operation(summary = "입금 확인 (판매자)", description = "판매자가 카카오페이 입금을 확인했다. PAYMENT_PENDING → PAID.")
    @PostMapping("/{tradeId}/confirm-payment")
    public TradeResponse confirmPayment(
            @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @PathVariable("tradeId") @Positive long tradeId) {
        return assembler.toResponse(tradeService.confirmPayment(tradeId, memberId), memberId);
    }

    @Operation(summary = "구매 확정 (구매자)",
            description = "물건을 받은 구매자가 확정한다. PAID → COMPLETED. Market 상품은 판매완료, 같은 상품의 다른 문의는 자동 취소.")
    @PostMapping("/{tradeId}/complete")
    public TradeResponse complete(
            @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @PathVariable("tradeId") @Positive long tradeId) {
        return assembler.toResponse(tradeService.complete(tradeId, memberId), memberId);
    }

    @Operation(summary = "거래 취소 (구매자·판매자)", description = "입금 확인(PAID) 전까지만 취소할 수 있다.")
    @PostMapping("/{tradeId}/cancel")
    public TradeResponse cancel(
            @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @PathVariable("tradeId") @Positive long tradeId,
            @Valid @RequestBody(required = false) CancelTradeRequest request) {
        String reason = request == null ? null : request.reason();
        return assembler.toResponse(tradeService.cancel(tradeId, memberId, reason), memberId);
    }
}
