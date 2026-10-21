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
@Tag(name = "Trade", description = "거래: 결제 요청(판매자) → 모의 결제(구매자) → 구매 확정 / 취소·환불")
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

    @Operation(summary = "판매자의 결제 요청",
            description = "상품·구매자·기존 채팅방을 확인하고 상품 예약과 모의 결제 주문을 생성합니다.")
    @PostMapping
    public ResponseEntity<TradeResponse> create(
            @Parameter(description = "요청자 회원 번호 (판매자)") @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @Parameter(description = "재시도해도 거래가 두 건 생기지 않게 하는 키 (UUID 권장)")
            @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CreateTradeRequest request) {
        CreateResult result = tradeService.create(memberId, request.productId(), request.buyerId(), request.chatRoomId(), idempotencyKey);
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
            description = "내가 구매자 또는 판매자인 거래. role·status·productId·chatRoomId로 거른다. "
                    + "판매자가 productId로 조회하면 그 상품에 문의한 구매자별 거래 목록이 된다.")
    @GetMapping
    public TradeListResponse list(
            @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @RequestParam(name = "role", required = false) TradeRole role,
            @RequestParam(name = "status", required = false) TradeStatus status,
            @RequestParam(name = "productId", required = false) @Positive Long productId,
            @RequestParam(name = "chatRoomId", required = false) @Positive Long chatRoomId) {
        List<Trade> trades = tradeService.list(memberId, role, status, productId, chatRoomId);
        return assembler.toListResponse(trades, memberId);
    }

    @Operation(summary = "결제 요청 재시도 (판매자)", description = "준비 상태 REQUESTED에서 예약·주문 생성을 재개합니다.")
    @PostMapping("/{tradeId}/request-payment")
    public TradeResponse requestPayment(
            @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @PathVariable("tradeId") @Positive long tradeId) {
        return assembler.toResponse(tradeService.requestPayment(tradeId, memberId), memberId);
    }

    @Operation(summary = "모의 결제 (구매자)", description = "Payment의 가상 잔액을 판매자에게 보냅니다. PAYMENT_REQUESTED → PAID.")
    @PostMapping("/{tradeId}/pay")
    public TradeResponse pay(
            @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @PathVariable("tradeId") @Positive long tradeId) {
        return assembler.toResponse(tradeService.pay(tradeId, memberId), memberId);
    }

    @Operation(summary = "구매 확정 (구매자)",
            description = "물건을 받은 구매자가 확정한다. PAID → COMPLETED. Market 상품은 판매 완료.")
    @PostMapping("/{tradeId}/complete")
    public TradeResponse complete(
            @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @PathVariable("tradeId") @Positive long tradeId) {
        return assembler.toResponse(tradeService.complete(tradeId, memberId), memberId);
    }

    @Operation(summary = "거래 취소 (구매자·판매자)", description = "구매 확정 전까지 취소할 수 있다. 결제 완료 건은 환불한다.")
    @PostMapping("/{tradeId}/cancel")
    public TradeResponse cancel(
            @RequestHeader(MEMBER_HEADER) @Positive long memberId,
            @PathVariable("tradeId") @Positive long tradeId,
            @Valid @RequestBody(required = false) CancelTradeRequest request) {
        String reason = request == null ? null : request.reason();
        return assembler.toResponse(tradeService.cancel(tradeId, memberId, reason), memberId);
    }
}
