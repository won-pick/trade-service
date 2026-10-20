# WON-pick Trade 서버

우리WON 청년 IT 아카데미(A·B반) 교내 중고거래 서비스 **WON-pick**의 거래(Trade) 서버입니다.
판매자가 채팅에서 **송금 요청**을 보내는 순간 거래가 생기고, 입금 확인·구매 확정까지 Market·Payment 서버를 차례로 부르는 조율자입니다.

## 서버 구성도

```
                     React (5173)
          ┌────────────────┼──────────────────┐
          │ 상품·회원       │ 송금 요청·거래     │ 채팅방·메시지 (Vite 프록시)
          ▼                ▼                  ▼
   Market (8082)  ◀── Trade (8083) ──▶  Payment (8084)      Chat (8080)
   상품 조회·예약·      │  trade_db       송금 요청·승인·취소
   판매 완료            │                     ┊
                        │                     ▼
                        └─ _links.chat ─▶  카카오페이 송금 링크 (구매자가 앱에서 직접 송금)
```

- 문의·대화는 **Chat 채팅방**이 맡습니다. 한 상품에 여러 구매자가 각자 채팅할 수 있습니다.
- 판매자가 채팅방에서 "송금 요청"을 누르면 **React가 Trade `POST /trades`를 직접** 부릅니다.
- Trade는 Chat을 부르지 않습니다. 요청에 담긴 `chatRoomId`를 저장하고 `_links.chat`으로 알려 줄 뿐입니다.

| 서버 | 포트 | DB 스키마 |
| --- | --- | --- |
| Chat | 8080 | 메모리 저장 (재시작 시 초기화) |
| Market | 8082 | market_db |
| **Trade** | **8083** | **trade_db** |
| Payment | 8084 (미정) | (Payment 담당) |

## 상태 전이표

| 지금 상태 | 행위 (주소) | 누가 | 다음 상태 | 함께 일어나는 일 |
| --- | --- | --- | --- | --- |
| (없음) | 송금 요청 `POST /trades` | 판매자 | PAYMENT_REQUESTED | Market 상품 확인·예약(RESERVED), Payment 송금 요청 생성. 진행 중인 거래가 있는 상품이면 409 |
| PAYMENT_REQUESTED | `POST /trades/{id}/confirm-payment` | 판매자 | PAID | Payment 승인 |
| PAYMENT_REQUESTED | `POST /trades/{id}/cancel` | 구매자·판매자 | CANCELED | Payment 취소 + Market 예약 해제(ON_SALE) |
| PAID | `POST /trades/{id}/complete` | 구매자 | COMPLETED | Market 판매 완료(SOLD) |
| COMPLETED · CANCELED | 없음 | — | (끝) | 다른 상태로 가는 요청 409, 같은 요청 재전송 200 |

- 입금이 확인된(PAID) 뒤에는 취소할 수 없습니다. 직송금이라 서버가 돈을 되돌릴 수 없기 때문입니다.
- 송금 요청 도중 Market 예약이나 Payment 생성이 실패하면, 앞에서 한 일을 거꾸로 되돌리고 거래를 CANCELED(사유: 실패 단계)로 남깁니다.
- 거래가 취소되면 상품이 다시 판매 중이 되어, 판매자가 다른 채팅방의 구매자에게 송금 요청을 보낼 수 있습니다.

## API

| 기능 | 메서드 · 주소 | 누가 | 요청 본문 | 성공 | 주요 실패 |
| --- | --- | --- | --- | --- | --- |
| 송금 요청 (거래 생성) | `POST /trades` (+ `Idempotency-Key`) | 판매자 | `{productId, buyerId, chatRoomId}` | 201 (같은 키 재시도는 200) | 403 ROLE_NOT_ALLOWED, 404 PRODUCT_NOT_FOUND, 409 PRODUCT_NOT_AVAILABLE, 422 SELF_PURCHASE, 502 |
| 거래 조회 | `GET /trades/{id}` | 당사자 | — | 200 | 403 NOT_TRADE_PARTICIPANT, 404 TRADE_NOT_FOUND |
| 내 거래 목록 | `GET /trades?role=&status=&productId=&chatRoomId=` | 본인 | — | 200 | 400 |
| 입금 확인 | `POST /trades/{id}/confirm-payment` | 판매자 | — | 200 | 403 ROLE_NOT_ALLOWED, 409, 502 |
| 구매 확정 | `POST /trades/{id}/complete` | 구매자 | — | 200 | 403 ROLE_NOT_ALLOWED, 409, 502 |
| 거래 취소 | `POST /trades/{id}/cancel` | 당사자 | `{reason}` (선택) | 200 | 409 INVALID_STATUS, 502 |

모든 요청에 `X-Member-Id` 헤더(회원 번호)가 필요합니다. 금액·상품 제목·판매자는 요청으로 받지 않고 Market에서 가져와 스냅샷으로 저장합니다.
오류는 모두 ProblemDetail(`application/problem+json`) + `code` 필드입니다. 다른 서버가 응답하지 않으면 502 `UPSTREAM_UNAVAILABLE`입니다.

### HATEOAS (`_links`)

| 상태 | 구매자 | 판매자 |
| --- | --- | --- |
| PAYMENT_REQUESTED | self, product, chat, payment, transfer, cancel | self, product, chat, payment, confirm-payment, cancel |
| PAID | self, product, chat, payment, complete | self, product, chat, payment |
| COMPLETED | self, product, payment | self, product, payment |
| CANCELED | self, product | self, product |

`product`·`chat`·`payment`는 다른 서버의 자원, `transfer`는 판매자의 카카오페이 송금 링크입니다.

## Trade가 부르는 다른 서버 API

| 서버 | 메서드 · 주소 | 요청 | 기대 응답 |
| --- | --- | --- | --- |
| Market | `GET /products/{id}` | — | `{productId, title, price, sellerId, status, ...}` (status: ON_SALE / RESERVED / SOLD) |
| Market | `POST /products/{id}/reservation` | `{tradeId}` | 200 (이미 예약이면 409 PRODUCT_NOT_AVAILABLE) |
| Market | `DELETE /products/{id}/reservation` | `{tradeId}` | 200 |
| Market | `POST /products/{id}/complete` | `{tradeId}` | 200 |
| Payment | `POST /payments` + `Idempotency-Key` | `{tradeId, payerId, payeeId, amount, method: "KAKAOPAY_TRANSFER"}` | 201 `{paymentId, status: "READY", transferUrl}` |
| Payment | `POST /payments/{id}/approve` | — | 200 |
| Payment | `POST /payments/{id}/cancel` | — | 200 |

## DB 스키마 (trade_db)

`src/main/resources/schema.sql` — 서버가 시작할 때 자동 실행됩니다 (`CREATE TABLE IF NOT EXISTS`).
상태 이름이 바뀌었으므로 이전 버전으로 만든 DB가 있으면 `DROP DATABASE trade_db;` 후 다시 실행하세요.

- `trades` — 거래. 상품 제목·가격은 송금 요청 시점의 스냅샷, `chat_room_id`는 송금 요청을 보낸 채팅방. `active_product_id` UNIQUE로 한 상품에 진행 중인 거래는 하나뿐
- `trade_idempotency_keys` — 송금 요청 멱등 키
- `trade_status_histories` — 상태 변경 이력

## 실행

1. MySQL 실행 (기본: `root` / `1234`, 다르면 환경 변수 `DB_USERNAME`, `DB_PASSWORD`). `trade_db`는 자동 생성됩니다.
2. 다른 서버 주소는 `application.yml`의 `servers.*`. 서버들이 서로 다른 PC에 있으면 `localhost` 대신 그 PC의 IP를 넣습니다.
3. 아직 없는 서버는 Trade 서버 안의 가짜(`/stub/**`)로 대신합니다.

   | 프로필 | Market | Payment | 언제 |
   | --- | --- | --- | --- |
   | (없음) | 실제 8082 | 실제 8084 | 모든 서버가 준비됐을 때 |
   | `stub-pay` | 실제 8082 | 가짜 | **지금** — Payment API만 아직 없음 |
   | `stub` | 가짜 | 가짜 | Trade 혼자 테스트 (`http/trade-flow.http`, 판매자 100번) |

   ```
   ./gradlew bootRun --args='--spring.profiles.active=stub-pay'
   ```
4. Swagger UI: http://localhost:8083/swagger-ui.html
