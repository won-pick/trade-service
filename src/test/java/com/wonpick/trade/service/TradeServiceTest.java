package com.wonpick.trade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.wonpick.trade.client.ChatClient;
import com.wonpick.trade.client.MarketClient;
import com.wonpick.trade.client.MarketClient.MarketProduct;
import com.wonpick.trade.client.PaymentClient;
import com.wonpick.trade.domain.Trade;
import com.wonpick.trade.domain.TradeRole;
import com.wonpick.trade.domain.TradeStatus;
import com.wonpick.trade.exception.ErrorCode;
import com.wonpick.trade.exception.TradeException;
import com.wonpick.trade.repository.IdempotencyKeyRepository;
import com.wonpick.trade.repository.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * TradeService만 검사하는 단위 테스트다.
 *
 * [검사 대상] 실제 TradeService와 실제 Trade/TradeStatus의 역할·상태 판단.
 * [대체 대상] DB에 접근하는 Repository, HTTP를 보내는 Client, 실제 DB 트랜잭션 관리자.
 *
 * SpringBootTest를 사용하지 않으므로 Spring 서버, MySQL, 다른 팀 서버를 켤 필요가 없다.
 * MockitoExtension이 각 테스트마다 새로운 Mock을 준비하므로 다른 테스트의 호출 기록도 섞이지 않는다.
 * Service가 Mock인 것은 아니다. 실제 메서드를 호출해야 업무 규칙을 검증할 수 있다.
 *
 * BDDMockito의 given(...).willReturn(...)은 사전 조건을, then(...).should()는 호출 결과를 표현한다.
 * void 메서드의 실패 조건은 willThrow(...).given(mock).메서드(...)로 설정한다.
 * assertThat은 반환값, assertThatThrownBy는 실제 Service 실행에서 발생한 예외를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TradeService 단위 테스트")
class TradeServiceTest {

    // 한 테스트 안에서 여러 종류의 ID를 혼동하지 않도록 서로 다른 값으로 둔다.
    private static final long ID = 1L;
    private static final long PRODUCT = 7L;
    private static final long BUYER = 3L;
    private static final long SELLER = 100L;
    private static final long ROOM = 9L;
    private static final long ORDER = 20L;
    private static final long STRANGER = 999L;
    private static final long PRICE = 10_000L;
    private static final String TITLE = "테스트 교재";
    private static final String KEY = "seller-payment-request-001";
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 29, 10, 0);

    @Mock TradeRepository trades;
    @Mock IdempotencyKeyRepository keys;
    @Mock MarketClient market;
    @Mock PaymentClient payment;
    @Mock ChatClient chat;
    @Mock PlatformTransactionManager manager;

    private TradeService service;

    @BeforeEach
    void setUp() {
        // 모든 의존성을 명시적으로 넘긴다. 실제 Repository/HTTP Client가 생성되지 않는다.
        // Service 내부의 TransactionTemplate은 실제로 실행하되, 그 관리자는 Mock으로 대체한다.
        service = new TradeService(trades, keys, market, payment, chat, manager);
    }

    @Nested
    @DisplayName("create: 판매자의 거래 생성")
    class CreateTests {

        @Test
        @DisplayName("정상 요청은 Market의 상품 정보를 저장하고 예약·주문 생성 후 결제 대기를 반환한다")
        void createsTradeUsingMarketSnapshot() {
            // Given: 처음 받는 키이고, 판매 중인 상품에 아직 열린 거래가 없다.
            given(trades.claimProduct(ID)).willReturn(1);
            given(keys.findTradeId(KEY)).willReturn(Optional.empty());
            givenEligibleProduct(PRICE);
            given(trades.insert(PRODUCT, TITLE, PRICE, BUYER, SELLER)).willReturn(ID);
            Trade preparing = trade(TradeStatus.REQUESTED, null);
            Trade ready = trade(TradeStatus.PAYMENT_REQUESTED, ORDER);
            // requestPayment가 변경 전/후에 각각 조회하므로 서로 다른 상태를 순서대로 반환한다.
            given(trades.findById(ID)).willReturn(Optional.of(preparing)).willReturn(Optional.of(ready));
            given(payment.createOrder(preparing)).willReturn(ORDER);
            given(trades.toPaymentPending(ID, ORDER)).willReturn(1);
            givenTransactions();

            // When: 실제 Service에 판매자의 결제 요청을 전달한다.
            TradeService.CreateResult result = service.create(SELLER, PRODUCT, BUYER, ROOM, KEY);

            // Then: 신규 생성 여부뿐 아니라 구매자·판매자·가격과 최종 상태도 검사한다.
            assertThat(result.created()).isTrue();
            assertThat(result.trade()).isEqualTo(ready);
            assertThat(result.trade().buyerId()).isEqualTo(BUYER);
            assertThat(result.trade().sellerId()).isEqualTo(SELLER);
            assertThat(result.trade().price()).isEqualTo(PRICE);
            then(chat).should().validateRoom(ROOM, PRODUCT, BUYER, SELLER);
            then(trades).should().insert(PRODUCT, TITLE, PRICE, BUYER, SELLER);
            then(trades).should().updateChatRoom(ID, ROOM);
            then(keys).should().save(KEY, ID);
            then(trades).should().insertHistory(ID, null, TradeStatus.REQUESTED, SELLER);
            var order = inOrder(trades, market, payment);
            then(trades).should(order).claimProduct(ID);
            then(market).should(order).reserve(PRODUCT, ID);
            then(payment).should(order).createOrder(preparing);
            then(trades).should(order).toPaymentPending(ID, ORDER);
            then(trades).should(order).insertHistory(ID, TradeStatus.REQUESTED,
                    TradeStatus.PAYMENT_REQUESTED, SELLER);
        }

        @ParameterizedTest(name = "가격 {0}원은 생성 가능")
        @ValueSource(longs = {1, 2_147_483_647L})
        @DisplayName("멱등 키가 없어도 최소 유료 금액과 Market 최대 가격을 그대로 저장한다")
        void createsWithoutKeyAtPriceBoundaries(long price) {
            // Given: 가격 경계값을 가진 정상 상품. 키는 선택 항목이므로 null로 요청한다.
            given(trades.claimProduct(ID)).willReturn(1);
            givenEligibleProduct(price);
            given(trades.insert(PRODUCT, TITLE, price, BUYER, SELLER)).willReturn(ID);
            Trade preparing = trade(TradeStatus.REQUESTED, null, price);
            Trade ready = trade(TradeStatus.PAYMENT_REQUESTED, ORDER, price);
            given(trades.findById(ID)).willReturn(Optional.of(preparing)).willReturn(Optional.of(ready));
            given(payment.createOrder(preparing)).willReturn(ORDER);
            given(trades.toPaymentPending(ID, ORDER)).willReturn(1);
            givenTransactions();

            // When
            TradeService.CreateResult result = service.create(SELLER, PRODUCT, BUYER, ROOM, null);

            // Then: 화면이 보낸 임의 금액이 아니라 Market 응답의 가격이 저장된다.
            assertThat(result.created()).isTrue();
            assertThat(result.trade().price()).isEqualTo(price);
            then(trades).should().insert(PRODUCT, TITLE, price, BUYER, SELLER);
            then(keys).shouldHaveNoInteractions();
        }

        @ParameterizedTest
        @EnumSource(value = TradeStatus.class, names = {"PAYMENT_REQUESTED", "PAID", "COMPLETED", "CANCELED"})
        @DisplayName("같은 키·같은 요청의 재전송은 기존 거래를 반환하고 외부 요청을 반복하지 않는다")
        void replaysExistingKeyWithoutRepeatingSideEffects(TradeStatus status) {
            // Given: 이 키로 생성한 거래가 이미 있다. 현재 상태가 무엇이든 같은 요청의 결과다.
            Trade existing = trade(status, ORDER);
            given(keys.findTradeId(KEY)).willReturn(Optional.of(ID));
            given(trades.findById(ID)).willReturn(Optional.of(existing));

            // When
            TradeService.CreateResult result = service.create(SELLER, PRODUCT, BUYER, ROOM, KEY);

            // Then: 기존 거래를 돌려주는 것이지 새로운 결제 주문을 만들면 안 된다.
            assertThat(result.created()).isFalse();
            assertThat(result.trade()).isEqualTo(existing);
            then(market).shouldHaveNoInteractions();
            then(payment).shouldHaveNoInteractions();
            then(chat).shouldHaveNoInteractions();
            then(manager).shouldHaveNoInteractions();
            then(trades).should(never()).insert(anyLong(), anyString(), anyLong(), anyLong(), anyLong());
            then(keys).should(never()).save(anyString(), anyLong());
        }

        @ParameterizedTest(name = "판매자={0}, 상품={1}, 구매자={2}, 방={3}")
        @CsvSource({"101, 7, 3, 9", "100, 8, 3, 9", "100, 7, 4, 9", "100, 7, 3, 10"})
        @DisplayName("같은 키를 다른 판매자·상품·구매자·채팅방으로 재사용하면 거절한다")
        void rejectsKeyReusedForDifferentRequest(long seller, long product, long buyer, long room) {
            // Given: 기존 키는 원래 요청(SELLER, PRODUCT, BUYER, ROOM)에 연결돼 있다.
            given(keys.findTradeId(KEY)).willReturn(Optional.of(ID));
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.PAID, ORDER)));

            // When / Then: 예외를 기대할 때도 람다 안에서 실제 검사할 메서드를 호출한다.
            assertError(() -> service.create(seller, product, buyer, room, KEY), ErrorCode.INVALID_STATUS);
            then(market).shouldHaveNoInteractions();
            then(payment).shouldHaveNoInteractions();
            then(chat).shouldHaveNoInteractions();
            then(keys).should(never()).save(anyString(), anyLong());
        }

        @Test
        @DisplayName("기존 키의 거래가 준비 상태라면 같은 거래에서 예약·주문 생성을 재개한다")
        void resumesPreparingTradeFromKey() {
            // Given: 거래 저장은 끝났지만 외부 요청이 중단돼 REQUESTED로 남아 있다.
            given(trades.claimProduct(ID)).willReturn(1);
            Trade preparing = trade(TradeStatus.REQUESTED, null);
            Trade ready = trade(TradeStatus.PAYMENT_REQUESTED, ORDER);
            given(keys.findTradeId(KEY)).willReturn(Optional.of(ID));
            given(trades.findById(ID)).willReturn(Optional.of(preparing)).willReturn(Optional.of(preparing)).willReturn(Optional.of(ready));
            given(payment.createOrder(preparing)).willReturn(ORDER);
            given(trades.toPaymentPending(ID, ORDER)).willReturn(1);
            givenTransactions();

            // When
            TradeService.CreateResult result = service.create(SELLER, PRODUCT, BUYER, ROOM, KEY);

            // Then: 같은 거래 ID로 재개한다. 거래 행을 하나 더 만들지 않는다.
            assertThat(result.created()).isFalse();
            assertThat(result.trade()).isEqualTo(ready);
            then(market).should().reserve(PRODUCT, ID);
            then(payment).should().createOrder(preparing);
            then(trades).should(never()).insert(anyLong(), anyString(), anyLong(), anyLong(), anyLong());
            then(chat).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("새 키로 요청해도 같은 상품·구매자의 열린 거래가 있으면 그 거래에 키만 연결한다")
        void reusesOpenTradeAndAssociatesNewKey() {
            // Given: 키는 새것이지만 같은 상품·구매자의 결제 대기 거래가 있다.
            Trade existing = trade(TradeStatus.PAYMENT_REQUESTED, ORDER);
            given(keys.findTradeId(KEY)).willReturn(Optional.empty());
            given(market.getProduct(PRODUCT)).willReturn(product(PRICE, "RESERVED"));
            given(trades.findOpenByProductAndBuyer(PRODUCT, BUYER)).willReturn(Optional.of(existing));

            // When
            TradeService.CreateResult result = service.create(SELLER, PRODUCT, BUYER, ROOM, KEY);

            // Then: 예약 때문에 상품이 RESERVED여도 기존 거래 조회·재시도는 가능하다.
            assertThat(result.created()).isFalse();
            assertThat(result.trade()).isEqualTo(existing);
            then(keys).should().save(KEY, ID);
            then(trades).should(never()).insert(anyLong(), anyString(), anyLong(), anyLong(), anyLong());
            then(market).should(never()).reserve(anyLong(), anyLong());
            then(payment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("키 없는 요청도 열린 거래를 재사용하며 키 저장을 호출하지 않는다")
        void reusesOpenTradeWithoutKey() {
            // Given
            Trade existing = trade(TradeStatus.PAID, ORDER);
            given(market.getProduct(PRODUCT)).willReturn(product(PRICE, "RESERVED"));
            given(trades.findOpenByProductAndBuyer(PRODUCT, BUYER)).willReturn(Optional.of(existing));

            // When
            TradeService.CreateResult result = service.create(SELLER, PRODUCT, BUYER, ROOM, null);

            // Then
            assertThat(result.created()).isFalse();
            assertThat(result.trade()).isEqualTo(existing);
            then(keys).shouldHaveNoInteractions();
            then(payment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("열린 거래의 채팅방과 요청 채팅방이 다르면 저장·결제를 실행하지 않는다")
        void rejectsDifferentRoomForOpenTrade() {
            // Given
            given(market.getProduct(PRODUCT)).willReturn(product(PRICE, "ON_SALE"));
            given(trades.findOpenByProductAndBuyer(PRODUCT, BUYER))
                    .willReturn(Optional.of(trade(TradeStatus.PAYMENT_REQUESTED, ORDER)));

            // When / Then
            assertError(() -> service.create(SELLER, PRODUCT, BUYER, ROOM + 1, null), ErrorCode.INVALID_INPUT);
            then(keys).shouldHaveNoInteractions();
            then(payment).shouldHaveNoInteractions();
            then(trades).should(never()).insert(anyLong(), anyString(), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("상품 판매자가 아닌 회원은 거래를 생성할 수 없다")
        void rejectsNonOwnerBeforeCheckingRoom() {
            // Given: Market이 알려 준 판매자는 SELLER다.
            given(market.getProduct(PRODUCT)).willReturn(product(PRICE, "ON_SALE"));

            // When / Then
            assertError(() -> service.create(STRANGER, PRODUCT, BUYER, ROOM, null), ErrorCode.ROLE_NOT_ALLOWED);
            then(chat).shouldHaveNoInteractions();
            then(trades).shouldHaveNoInteractions();
            then(payment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("판매자가 자기 자신을 구매자로 지정하면 거래를 생성하지 않는다")
        void rejectsSelfPurchase() {
            // Given
            given(market.getProduct(PRODUCT)).willReturn(product(PRICE, "ON_SALE"));

            // When / Then
            assertError(() -> service.create(SELLER, PRODUCT, SELLER, ROOM, null), ErrorCode.SELF_PURCHASE);
            then(chat).shouldHaveNoInteractions();
            then(trades).shouldHaveNoInteractions();
            then(payment).shouldHaveNoInteractions();
        }

        @ParameterizedTest
        @ValueSource(strings = {"RESERVED", "SOLD"})
        @DisplayName("열린 거래가 없고 상품이 판매 중이 아니면 새 거래를 생성하지 않는다")
        void rejectsUnavailableProduct(String status) {
            // Given: 여기서는 기존 거래가 없는 신규 요청을 검사한다.
            given(market.getProduct(PRODUCT)).willReturn(product(PRICE, status));
            given(trades.findOpenByProductAndBuyer(PRODUCT, BUYER)).willReturn(Optional.empty());

            // When / Then
            assertError(() -> service.create(SELLER, PRODUCT, BUYER, ROOM, null), ErrorCode.PRODUCT_NOT_AVAILABLE);
            then(trades).should(never()).insert(anyLong(), anyString(), anyLong(), anyLong(), anyLong());
            then(payment).shouldHaveNoInteractions();
            then(manager).shouldHaveNoInteractions();
        }

        @ParameterizedTest
        @ValueSource(longs = {0, -1})
        @DisplayName("0원 이하의 상품은 유료 결제를 요청할 수 없다")
        void rejectsNonPositivePrice(long price) {
            // Given: 0은 최소 거절 경계값, -1은 그보다 작은 값이다.
            givenEligibleProduct(price);

            // When / Then
            assertError(() -> service.create(SELLER, PRODUCT, BUYER, ROOM, null), ErrorCode.INVALID_INPUT);
            then(trades).should(never()).insert(anyLong(), anyString(), anyLong(), anyLong(), anyLong());
            then(payment).shouldHaveNoInteractions();
            then(manager).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("Market 조회 실패 시 거래 저장과 채팅·결제를 실행하지 않는다")
        void stopsWhenMarketLookupFails() {
            // Given: Client는 모킹한다. 실제 네트워크를 끊는 테스트가 아니다.
            TradeException failure = upstreamFailure();
            given(market.getProduct(PRODUCT)).willThrow(failure);

            // When / Then: 같은 예외가 전달되는지와 후속 호출이 없는지를 함께 확인한다.
            assertThatThrownBy(() -> service.create(SELLER, PRODUCT, BUYER, ROOM, null)).isSameAs(failure);
            then(trades).shouldHaveNoInteractions();
            then(chat).shouldHaveNoInteractions();
            then(payment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("채팅방 검증 실패 시 거래를 저장하거나 예약·결제하지 않는다")
        void stopsWhenRoomValidationFails() {
            // Given: void 메서드의 예외는 BDDMockito.willThrow로 설정한다.
            given(market.getProduct(PRODUCT)).willReturn(product(PRICE, "ON_SALE"));
            TradeException failure = new TradeException(ErrorCode.INVALID_INPUT, "채팅방 참여자 불일치");
            willThrow(failure).given(chat).validateRoom(ROOM, PRODUCT, BUYER, SELLER);

            // When / Then
            assertThatThrownBy(() -> service.create(SELLER, PRODUCT, BUYER, ROOM, null)).isSameAs(failure);
            then(trades).shouldHaveNoInteractions();
            then(market).should(never()).reserve(anyLong(), anyLong());
            then(payment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("생성 중 DB 저장이 실패하면 트랜잭션 롤백을 요청하고 외부 결제를 실행하지 않는다")
        void requestsRollbackWhenCreationPersistenceFails() {
            // Given: 거래 생성 트랜잭션의 마지막 키 저장에서 DB 예외가 발생한다.
            given(keys.findTradeId(KEY)).willReturn(Optional.empty());
            givenEligibleProduct(PRICE);
            given(trades.insert(PRODUCT, TITLE, PRICE, BUYER, SELLER)).willReturn(ID);
            DuplicateKeyException failure = new DuplicateKeyException("이미 사용한 키");
            willThrow(failure).given(keys).save(KEY, ID);
            givenTransactions();

            // When / Then: 실제 DB 행이 복구됐다는 뜻은 아니다. 관리자에게 롤백을 요청했는지를 검사한다.
            assertThatThrownBy(() -> service.create(SELLER, PRODUCT, BUYER, ROOM, KEY)).isSameAs(failure);
            then(manager).should().rollback(any());
            then(manager).should(never()).commit(any());
            then(market).should(never()).reserve(anyLong(), anyLong());
            then(payment).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("get / list: 거래 조회")
    class ReadTests {
        @ParameterizedTest
        @ValueSource(longs = {BUYER, SELLER})
        @DisplayName("구매자와 판매자는 거래를 조회할 수 있다")
        void participantCanRead(long member) {
            // Given
            Trade existing = trade(TradeStatus.PAID, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(existing));
            // When
            Trade result = service.get(ID, member);
            // Then: Service가 실제로 접근 권한을 검사한 뒤 반환하는 결과다.
            assertThat(result).isEqualTo(existing);
            then(payment).shouldHaveNoInteractions();
            then(manager).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("존재하지 않는 거래는 TRADE_NOT_FOUND 예외다")
        void missingTradeIsRejected() {
            // Given
            given(trades.findById(ID)).willReturn(Optional.empty());
            // When / Then: HTTP 404는 Controller 책임이므로 여기서는 업무 오류 코드를 확인한다.
            assertError(() -> service.get(ID, BUYER), ErrorCode.TRADE_NOT_FOUND);
        }

        @Test
        @DisplayName("구매자·판매자가 아닌 회원의 조회는 거절한다")
        void strangerCannotRead() {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.PAID, ORDER)));
            // When / Then
            assertError(() -> service.get(ID, STRANGER), ErrorCode.NOT_TRADE_PARTICIPANT);
        }

        @Test
        @DisplayName("목록 필터와 조회자를 그대로 전달하고 결과를 반환한다")
        void forwardsListFilters() {
            // Given
            List<Trade> expected = List.of(trade(TradeStatus.PAID, ORDER));
            given(trades.findByViewer(BUYER, TradeRole.BUYER, TradeStatus.PAID, PRODUCT, ROOM)).willReturn(expected);
            // When
            List<Trade> result = service.list(BUYER, TradeRole.BUYER, TradeStatus.PAID, PRODUCT, ROOM);
            // Then: SQL 필터 구현은 Repository 테스트의 범위다. 여기서는 전달 누락을 검증한다.
            assertThat(result).containsExactlyElementsOf(expected);
            then(trades).should().findByViewer(BUYER, TradeRole.BUYER, TradeStatus.PAID, PRODUCT, ROOM);
        }

        @Test
        @DisplayName("필터가 없고 조회 결과가 없으면 빈 목록을 반환한다")
        void returnsEmptyListWithoutFilters() {
            // Given
            given(trades.findByViewer(BUYER, null, null, null, null)).willReturn(List.of());
            // When
            List<Trade> result = service.list(BUYER, null, null, null, null);
            // Then
            assertThat(result).isEmpty();
            then(trades).should().findByViewer(BUYER, null, null, null, null);
        }
    }

    @Nested
    @DisplayName("requestPayment: 중단된 결제 요청 재시도")
    class RequestPaymentTests {
        @Test
        @DisplayName("판매자가 준비 상태 거래를 예약하고 주문 생성 후 이력을 남긴다")
        void preparesPaymentInBusinessOrder() {
            // Given
            given(trades.claimProduct(ID)).willReturn(1);
            Trade before = trade(TradeStatus.REQUESTED, null);
            Trade after = trade(TradeStatus.PAYMENT_REQUESTED, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(before)).willReturn(Optional.of(after));
            given(payment.createOrder(before)).willReturn(ORDER);
            given(trades.toPaymentPending(ID, ORDER)).willReturn(1);
            givenTransactions();
            // When
            Trade result = service.requestPayment(ID, SELLER);
            // Then: 예약이 확보되기 전에 결제 주문이 생성되는 것을 막는 순서 검증이다.
            assertThat(result).isEqualTo(after);
            var order = inOrder(trades, market, payment);
            then(trades).should(order).claimProduct(ID);
            then(market).should(order).reserve(PRODUCT, ID);
            then(payment).should(order).createOrder(before);
            then(trades).should(order).toPaymentPending(ID, ORDER);
            then(trades).should(order).insertHistory(ID, TradeStatus.REQUESTED, TradeStatus.PAYMENT_REQUESTED, SELLER);
        }

        @Test
        @DisplayName("이미 결제 대기라면 외부 주문을 다시 생성하지 않는다")
        void alreadyRequestedIsIdempotent() {
            // Given
            Trade existing = trade(TradeStatus.PAYMENT_REQUESTED, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(existing));
            // When
            Trade result = service.requestPayment(ID, SELLER);
            // Then
            assertThat(result).isEqualTo(existing);
            then(payment).shouldHaveNoInteractions();
            then(market).shouldHaveNoInteractions();
            then(manager).shouldHaveNoInteractions();
        }

        @ParameterizedTest
        @ValueSource(longs = {BUYER, STRANGER})
        @DisplayName("구매자나 제삼자는 판매자의 결제 요청을 재개할 수 없다")
        void rejectsWrongRequester(long member) {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.REQUESTED, null)));
            // When / Then
            assertError(() -> service.requestPayment(ID, member),
                    member == BUYER ? ErrorCode.ROLE_NOT_ALLOWED : ErrorCode.NOT_TRADE_PARTICIPANT);
            then(market).shouldHaveNoInteractions();
            then(payment).shouldHaveNoInteractions();
        }

        @ParameterizedTest
        @EnumSource(value = TradeStatus.class, names = {"PAID", "COMPLETED", "CANCELED"})
        @DisplayName("결제 완료 또는 종료 상태에서는 결제 요청을 재개하지 않는다")
        void rejectsInvalidState(TradeStatus status) {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(status, ORDER)));
            // When / Then
            assertError(() -> service.requestPayment(ID, SELLER), ErrorCode.INVALID_STATUS);
            then(market).shouldHaveNoInteractions();
            then(payment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("다른 거래가 상품을 확보했다면 예약·주문을 호출하지 않는다")
        void rejectsClaimConflict() {
            // Given: UNIQUE 제약 충돌을 DB 없이 재현한다.
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.REQUESTED, null)));
            given(trades.claimProduct(ID)).willThrow(new DuplicateKeyException("이미 진행 중인 상품"));
            // When / Then
            assertError(() -> service.requestPayment(ID, SELLER), ErrorCode.PRODUCT_NOT_AVAILABLE);
            then(market).shouldHaveNoInteractions();
            then(payment).shouldHaveNoInteractions();
            then(manager).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("Market 예약 실패 시 Payment 호출과 상태 변경이 없다")
        void reservationFailureStopsOrderCreation() {
            // Given
            given(trades.claimProduct(ID)).willReturn(1);
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.REQUESTED, null)));
            TradeException failure = upstreamFailure();
            willThrow(failure).given(market).reserve(PRODUCT, ID);
            // When / Then
            assertThatThrownBy(() -> service.requestPayment(ID, SELLER)).isSameAs(failure);
            then(payment).shouldHaveNoInteractions();
            then(trades).should(never()).toPaymentPending(anyLong(), anyLong());
            then(manager).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("주문 생성 실패 시 준비 상태를 보존하고 다음 재시도를 기다린다")
        void orderFailureDoesNotAdvanceOrReleaseReservation() {
            // Given
            given(trades.claimProduct(ID)).willReturn(1);
            Trade before = trade(TradeStatus.REQUESTED, null);
            given(trades.findById(ID)).willReturn(Optional.of(before));
            TradeException failure = upstreamFailure();
            given(payment.createOrder(before)).willThrow(failure);
            // When / Then: 응답만 유실됐을 수도 있으므로 임의로 새 거래를 만들거나 예약을 풀지 않는다.
            assertThatThrownBy(() -> service.requestPayment(ID, SELLER)).isSameAs(failure);
            then(market).should().reserve(PRODUCT, ID);
            then(market).should(never()).release(anyLong(), anyLong());
            then(trades).should(never()).toPaymentPending(anyLong(), anyLong());
            then(trades).should(never()).insertHistory(anyLong(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("조건부 UPDATE가 0행이면 실제 변경 이력을 추가하지 않는다")
        void zeroUpdatedRowsDoNotProduceHistory() {
            // Given: Repository가 0을 반환하는 상황을 명시한다. 성공한 것으로 꾸미지 않는다.
            Trade before = trade(TradeStatus.REQUESTED, null);
            given(trades.findById(ID)).willReturn(Optional.of(before));
            given(payment.createOrder(before)).willReturn(ORDER);
            given(trades.claimProduct(ID)).willReturn(1);
            given(trades.toPaymentPending(ID, ORDER)).willReturn(0);
            givenTransactions();
            // When
            Trade result = service.requestPayment(ID, SELLER);
            // Then: 현재 구현은 다시 조회한 상태를 반환한다. 0행일 때 예외를 던지는 규칙은 없다.
            assertThat(result).isEqualTo(before);
            then(trades).should(never()).insertHistory(anyLong(), any(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("pay: 구매자의 모의 결제")
    class PayTests {
        @Test
        @DisplayName("Payment 성공 뒤에만 PAID로 저장하고 구매자 변경 이력을 남긴다")
        void paysBeforeRecordingPaidState() {
            // Given
            Trade before = trade(TradeStatus.PAYMENT_REQUESTED, ORDER);
            Trade after = trade(TradeStatus.PAID, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(before)).willReturn(Optional.of(after));
            given(trades.toPaid(ID)).willReturn(1);
            givenTransactions();
            // When
            Trade result = service.pay(ID, BUYER);
            // Then: 결제 실패인데 거래만 PAID가 되는 잘못된 순서를 방지한다.
            assertThat(result).isEqualTo(after);
            var order = inOrder(payment, trades);
            then(payment).should(order).pay(before);
            then(trades).should(order).toPaid(ID);
            then(trades).should(order).insertHistory(ID, TradeStatus.PAYMENT_REQUESTED, TradeStatus.PAID, BUYER);
        }

        @Test
        @DisplayName("이미 결제한 거래의 재요청은 잔액 차감을 반복하지 않는다")
        void alreadyPaidIsIdempotent() {
            // Given
            Trade existing = trade(TradeStatus.PAID, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(existing));
            // When
            Trade result = service.pay(ID, BUYER);
            // Then
            assertThat(result).isEqualTo(existing);
            then(payment).shouldHaveNoInteractions();
            then(manager).shouldHaveNoInteractions();
        }

        @ParameterizedTest
        @ValueSource(longs = {SELLER, STRANGER})
        @DisplayName("판매자나 제삼자는 구매자 대신 결제할 수 없다")
        void rejectsWrongPayer(long member) {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.PAYMENT_REQUESTED, ORDER)));
            // When / Then
            assertError(() -> service.pay(ID, member),
                    member == SELLER ? ErrorCode.ROLE_NOT_ALLOWED : ErrorCode.NOT_TRADE_PARTICIPANT);
            then(payment).shouldHaveNoInteractions();
            then(trades).should(never()).toPaid(anyLong());
        }

        @ParameterizedTest
        @EnumSource(value = TradeStatus.class, names = {"REQUESTED", "COMPLETED", "CANCELED"})
        @DisplayName("결제 대기가 아닌 상태는 결제를 실행하지 않는다")
        void rejectsInvalidState(TradeStatus status) {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(status, ORDER)));
            // When / Then
            assertError(() -> service.pay(ID, BUYER), ErrorCode.INVALID_STATUS);
            then(payment).shouldHaveNoInteractions();
            then(trades).should(never()).toPaid(anyLong());
        }

        @Test
        @DisplayName("잔액 부족 등 Payment 거절 시 상태·이력을 저장하지 않는다")
        void rejectedPaymentDoesNotSavePaidState() {
            // Given: 결제 대기 중인 거래를 준비한다.
            Trade before = trade(TradeStatus.PAYMENT_REQUESTED, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(before));

            // Payment가 잔액 부족으로 결제를 거절하도록 설정한다.
            TradeException failure =
                    new TradeException(ErrorCode.PAYMENT_REJECTED, "잔액이 부족합니다.");

            willThrow(failure).given(payment).pay(before);

            // When / Then: 구매자가 결제하면 그 예외가 전달돼야 한다.
            assertThatThrownBy(() -> service.pay(ID, BUYER))
                    .isSameAs(failure)
                    .hasMessage("잔액이 부족합니다.");

            // Then: 결제 완료 상태를 저장하면 안 된다.
            then(trades).should(never()).toPaid(anyLong());

            // Then: 결제 완료 이력도 저장하면 안 된다.
            then(trades).should(never())
                    .insertHistory(anyLong(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("결제 후 조건부 UPDATE가 0행이면 중복 상태 이력을 만들지 않는다")
        void zeroUpdatedRowsDoNotProduceHistory() {
            // Given
            Trade current = trade(TradeStatus.PAYMENT_REQUESTED, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(current));
            given(trades.toPaid(ID)).willReturn(0);
            givenTransactions();
            // When
            Trade result = service.pay(ID, BUYER);
            // Then
            assertThat(result).isEqualTo(current);
            then(payment).should().pay(current);
            then(trades).should(never()).insertHistory(anyLong(), any(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("complete: 구매자의 구매 확정")
    class CompleteTests {
        @Test
        @DisplayName("Market 판매 완료 뒤에만 거래를 COMPLETED로 저장한다")
        void completesProductBeforeTrade() {
            // Given
            Trade before = trade(TradeStatus.PAID, ORDER);
            Trade after = trade(TradeStatus.COMPLETED, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(before)).willReturn(Optional.of(after));
            given(trades.toCompleted(ID)).willReturn(1);
            givenTransactions();
            // When
            Trade result = service.complete(ID, BUYER);
            // Then
            assertThat(result).isEqualTo(after);
            var order = inOrder(market, trades);
            then(market).should(order).markSold(PRODUCT, ID);
            then(trades).should(order).toCompleted(ID);
            then(trades).should(order).insertHistory(ID, TradeStatus.PAID, TradeStatus.COMPLETED, BUYER);
            then(payment).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("이미 구매 확정한 거래는 판매 완료 API를 반복 호출하지 않는다")
        void alreadyCompletedIsIdempotent() {
            // Given
            Trade existing = trade(TradeStatus.COMPLETED, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(existing));
            // When
            Trade result = service.complete(ID, BUYER);
            // Then
            assertThat(result).isEqualTo(existing);
            then(market).shouldHaveNoInteractions();
            then(manager).shouldHaveNoInteractions();
        }

        @ParameterizedTest
        @ValueSource(longs = {SELLER, STRANGER})
        @DisplayName("판매자나 제삼자는 구매 확정을 할 수 없다")
        void rejectsWrongCompleter(long member) {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.PAID, ORDER)));
            // When / Then
            assertError(() -> service.complete(ID, member),
                    member == SELLER ? ErrorCode.ROLE_NOT_ALLOWED : ErrorCode.NOT_TRADE_PARTICIPANT);
            then(market).shouldHaveNoInteractions();
        }

        @ParameterizedTest
        @EnumSource(value = TradeStatus.class, names = {"REQUESTED", "PAYMENT_REQUESTED", "CANCELED"})
        @DisplayName("결제가 완료되지 않은 거래는 구매 확정할 수 없다")
        void rejectsInvalidState(TradeStatus status) {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(status, ORDER)));
            // When / Then
            assertError(() -> service.complete(ID, BUYER), ErrorCode.INVALID_STATUS);
            then(market).shouldHaveNoInteractions();
            then(trades).should(never()).toCompleted(anyLong());
        }

        @Test
        @DisplayName("Market 판매 완료 실패 시 거래 상태와 이력을 변경하지 않는다")
        void marketFailureDoesNotSaveCompletion() {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.PAID, ORDER)));
            TradeException failure = upstreamFailure();
            willThrow(failure).given(market).markSold(PRODUCT, ID);
            // When / Then
            assertThatThrownBy(() -> service.complete(ID, BUYER)).isSameAs(failure);
            then(trades).should(never()).toCompleted(anyLong());
            then(trades).should(never()).insertHistory(anyLong(), any(), any(), anyLong());
            then(manager).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("구매 확정 UPDATE가 0행이면 변경 이력을 추가하지 않는다")
        void zeroUpdatedRowsDoNotProduceHistory() {
            // Given
            Trade current = trade(TradeStatus.PAID, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(current));
            given(trades.toCompleted(ID)).willReturn(0);
            givenTransactions();
            // When
            Trade result = service.complete(ID, BUYER);
            // Then
            assertThat(result).isEqualTo(current);
            then(trades).should(never()).insertHistory(anyLong(), any(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("cancel: 거래 취소·환불")
    class CancelTests {
        @ParameterizedTest
        @CsvSource({"PAYMENT_REQUESTED, 3", "PAYMENT_REQUESTED, 100", "PAID, 3", "PAID, 100"})
        @DisplayName("구매자·판매자는 결제 전후에 취소할 수 있고 Payment 처리 후 예약을 해제한다")
        void cancelsPaymentBeforeReleasingProduct(TradeStatus status, long member) {
            // Given: 결제 전 취소/결제 후 환불의 구체적인 HTTP 선택은 PaymentClient의 책임이다.
            Trade before = trade(status, ORDER);
            Trade after = canceledTrade("일정이 맞지 않아요", member);
            given(trades.findById(ID)).willReturn(Optional.of(before)).willReturn(Optional.of(after));
            given(trades.toCanceled(ID, "일정이 맞지 않아요", member)).willReturn(1);
            givenTransactions();
            // When
            Trade result = service.cancel(ID, member, "일정이 맞지 않아요");
            // Then: Service는 저장된 주문 번호로 Payment 처리를 먼저 요청해야 한다.
            assertThat(result).isEqualTo(after);
            assertThat(result.cancelReason()).isEqualTo("일정이 맞지 않아요");
            assertThat(result.canceledBy()).isEqualTo(member);
            var order = inOrder(payment, market, trades);
            then(payment).should(order).cancelOrRefund(ORDER);
            then(market).should(order).release(PRODUCT, ID);
            then(trades).should(order).toCanceled(ID, "일정이 맞지 않아요", member);
            then(trades).should(order).insertHistory(ID, status, TradeStatus.CANCELED, member);
            then(payment).should(never()).createOrder(any());
        }

        @Test
        @DisplayName("주문 번호가 없는 준비 상태는 멱등 주문을 먼저 찾고 취소한다")
        void resolvesMissingOrderBeforeCanceling() {
            // Given: 주문 생성 응답이 유실됐을 가능성이 있는 준비 상태다.
            Trade before = trade(TradeStatus.REQUESTED, null);
            Trade after = canceledTrade(null, BUYER);
            given(trades.findById(ID)).willReturn(Optional.of(before)).willReturn(Optional.of(after));
            given(payment.createOrder(before)).willReturn(ORDER);
            given(trades.toCanceled(ID, null, BUYER)).willReturn(1);
            givenTransactions();
            // When
            Trade result = service.cancel(ID, BUYER, null);
            // Then: 기존 주문을 찾는 실제 멱등 HTTP 계약은 Client/통합 테스트에서 검증한다.
            assertThat(result).isEqualTo(after);
            var order = inOrder(payment, market, trades);
            then(payment).should(order).createOrder(before);
            then(payment).should(order).cancelOrRefund(ORDER);
            then(market).should(order).release(PRODUCT, ID);
            then(trades).should(order).toCanceled(ID, null, BUYER);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("취소 사유는 선택 항목이므로 null 또는 빈 문자열도 그대로 전달한다")
        void acceptsOptionalReason(String reason) {
            // Given
            Trade before = trade(TradeStatus.PAYMENT_REQUESTED, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(before)).willReturn(Optional.of(canceledTrade(reason, BUYER)));
            given(trades.toCanceled(ID, reason, BUYER)).willReturn(1);
            givenTransactions();
            // When
            Trade result = service.cancel(ID, BUYER, reason);
            // Then: 사유 공백 제거와 길이 검사는 Service의 현재 책임이 아니다.
            assertThat(result.status()).isEqualTo(TradeStatus.CANCELED);
            assertThat(result.cancelReason()).isEqualTo(reason);
            then(trades).should().toCanceled(ID, reason, BUYER);
        }

        @Test
        @DisplayName("이미 취소한 거래는 환불·예약 해제를 반복하지 않는다")
        void alreadyCanceledIsIdempotent() {
            // Given
            Trade existing = canceledTrade("기존 사유", BUYER);
            given(trades.findById(ID)).willReturn(Optional.of(existing));
            // When
            Trade result = service.cancel(ID, BUYER, "새로운 사유");
            // Then: 재요청의 새 사유로 기존 취소 기록을 덮어쓰지도 않는다.
            assertThat(result).isEqualTo(existing);
            assertThat(result.cancelReason()).isEqualTo("기존 사유");
            then(payment).shouldHaveNoInteractions();
            then(market).shouldHaveNoInteractions();
            then(manager).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("제삼자는 거래를 취소할 수 없다")
        void rejectsStranger() {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.PAYMENT_REQUESTED, ORDER)));
            // When / Then
            assertError(() -> service.cancel(ID, STRANGER, null), ErrorCode.NOT_TRADE_PARTICIPANT);
            then(payment).shouldHaveNoInteractions();
            then(market).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("구매 확정된 거래는 취소·환불하지 않는다")
        void rejectsCompletedTrade() {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.COMPLETED, ORDER)));
            // When / Then
            assertError(() -> service.cancel(ID, BUYER, null), ErrorCode.INVALID_STATUS);
            then(payment).shouldHaveNoInteractions();
            then(market).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("주문 번호 확인이 실패하면 취소·예약 해제·저장을 실행하지 않는다")
        void stopsWhenResolvingOrderFails() {
            // Given
            Trade before = trade(TradeStatus.REQUESTED, null);
            given(trades.findById(ID)).willReturn(Optional.of(before));
            TradeException failure = upstreamFailure();
            given(payment.createOrder(before)).willThrow(failure);
            // When / Then
            assertThatThrownBy(() -> service.cancel(ID, BUYER, null)).isSameAs(failure);
            then(payment).should(never()).cancelOrRefund(anyLong());
            then(market).shouldHaveNoInteractions();
            then(trades).should(never()).toCanceled(anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("Payment 취소·환불 실패 시 상품 예약과 거래 상태를 유지한다")
        void paymentFailureDoesNotReleaseOrSaveCancellation() {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.PAID, ORDER)));
            TradeException failure = upstreamFailure();
            willThrow(failure).given(payment).cancelOrRefund(ORDER);
            // When / Then
            assertThatThrownBy(() -> service.cancel(ID, BUYER, null)).isSameAs(failure);
            then(market).shouldHaveNoInteractions();
            then(trades).should(never()).toCanceled(anyLong(), any(), anyLong());
            then(trades).should(never()).insertHistory(anyLong(), any(), any(), anyLong());
            then(manager).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("예약 해제 실패 시 거래 취소를 저장하지 않아 재시도 가능 상태를 남긴다")
        void releaseFailureDoesNotSaveCancellation() {
            // Given
            given(trades.findById(ID)).willReturn(Optional.of(trade(TradeStatus.PAID, ORDER)));
            TradeException failure = upstreamFailure();
            willThrow(failure).given(market).release(PRODUCT, ID);
            // When / Then: 이미 성공한 Payment 처리를 실제로 되돌렸다고 주장하지 않는다.
            assertThatThrownBy(() -> service.cancel(ID, BUYER, null)).isSameAs(failure);
            then(payment).should().cancelOrRefund(ORDER);
            then(trades).should(never()).toCanceled(anyLong(), any(), anyLong());
            then(manager).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("취소 UPDATE가 0행이면 중복 취소 이력을 만들지 않는다")
        void zeroUpdatedRowsDoNotProduceHistory() {
            // Given
            Trade current = trade(TradeStatus.PAYMENT_REQUESTED, ORDER);
            given(trades.findById(ID)).willReturn(Optional.of(current));
            given(trades.toCanceled(ID, null, BUYER)).willReturn(0);
            givenTransactions();
            // When
            Trade result = service.cancel(ID, BUYER, null);
            // Then
            assertThat(result).isEqualTo(current);
            then(trades).should(never()).insertHistory(anyLong(), any(), any(), anyLong());
        }
    }

    // ---------- 공통 Given 도우미: 모든 테스트에 불필요한 스텁을 깔지 않고 필요한 경우에만 호출한다. ----------

    private void givenEligibleProduct(long price) {
        given(market.getProduct(PRODUCT)).willReturn(product(price, "ON_SALE"));
        given(trades.findOpenByProductAndBuyer(PRODUCT, BUYER)).willReturn(Optional.empty());
    }

    private void givenTransactions() {
        // TransactionTemplate은 실제로 콜백을 호출한다. DB 연결만 가짜 관리자로 대체한다.
        // 콜백을 통째로 모킹해 실행하지 않으면 그 안의 저장·이력 로직을 테스트할 수 없다.
        // 매 트랜잭션에 새 상태 객체를 줘서 생성/결제 요청의 두 트랜잭션도 서로 구분한다.
        given(manager.getTransaction(any(TransactionDefinition.class)))
                .willAnswer(invocation -> new SimpleTransactionStatus());
    }

    // ---------- 테스트 데이터: Mock이 아닌 실제 DTO/도메인 객체를 사용한다. ----------

    private static MarketProduct product(long price, String status) {
        return new MarketProduct(PRODUCT, TITLE, price, SELLER, status);
    }

    private static Trade trade(TradeStatus status, Long orderId) {
        return trade(status, orderId, PRICE);
    }

    private static Trade trade(TradeStatus status, Long orderId, long price) {
        return new Trade(ID, PRODUCT, TITLE, price, BUYER, SELLER, status,
                orderId, ROOM, null, null, TIME, TIME);
    }

    private static Trade canceledTrade(String reason, long canceledBy) {
        return new Trade(ID, PRODUCT, TITLE, PRICE, BUYER, SELLER, TradeStatus.CANCELED,
                ORDER, ROOM, reason, canceledBy, TIME, TIME);
    }

    private static TradeException upstreamFailure() {
        return new TradeException(ErrorCode.UPSTREAM_UNAVAILABLE, "연동 서버 응답 실패");
    }

    private static void assertError(Runnable when, ErrorCode expectedCode) {
        // 예외 종류만 맞는지 보는 것으로 끝내지 않고, 구체적인 업무 오류 코드까지 검사한다.
        assertThatThrownBy(when::run).isInstanceOfSatisfying(TradeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expectedCode));
    }
}
