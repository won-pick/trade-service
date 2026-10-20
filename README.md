# WON-pick Trade 서버

우리WON 청년 IT 아카데미(A·B반) 교내 중고거래 서비스 **WON-pick**의 거래(Trade) 서버입니다.
판매자가 채팅에서 **결제 요청**을 보내는 순간 거래가 생기고, 결제·구매 확정까지 Market·Payment 서버를 차례로 부르는 조율자입니다.

## 서버 구성도

```
                     React (5173)
          ┌────────────────┼──────────────────┐
          │ 상품·회원       │ 결제 요청·거래     │ 채팅방·메시지 (Vite 프록시)
          ▼                ▼                  ▼
   Market (8082)  ◀── Trade (8083) ──▶  Payment (8084)      Chat (8080)
   상품 조회·예약·      │  trade_db       주문 생성·잔액 이동
   판매 완료            │                (payment_db, 가상 잔액)
                        └─ _links.chat ─▶ Chat 채팅방
```

- 문의·대화는 **Chat 채팅방**이 맡습니다. 한 상품에 여러 구매자가 각자 채팅할 수 있습니다.
- 판매자가 채팅방에서 "결제 요청"을 누르면 **React가 Trade `POST /trades`를 직접** 부릅니다.
- Trade는 Chat을 부르지 않습니다. 요청에 담긴 `chatRoomId`를 저장하고 `_links.chat`으로 알려 줄 뿐입니다.
- 결제는 카카오페이 대신 Payment 서버의 **가상 잔액**(`accounts.balance`)을 옮깁니다.

| 서버 | 포트 | DB 스키마 |
| --- | --- | --- |
| Chat | 8080 | chat_db (`mysql` 프로필, 기본은 H2 메모리) |
| Market | 8082 | wonpick_market |
| **Trade** | **8083** | **trade_db** |
| Payment | 8084 | payment_db |

## 상태 전이표

| 지금 상태 | 행위 (주소) | 누가 | 다음 상태 | 함께 일어나는 일 |
| --- | --- | --- | --- | --- |
| (없음) | 결제 요청 `POST /trades` | 판매자 | PAYMENT_REQUESTED | Market 상품 확인·예약(RESERVED), Payment 주문 생성(PENDING). 진행 중인 거래가 있는 상품이면 409 |
| PAYMENT_REQUESTED | `POST /trades/{id}/pay` | 구매자 | PAID | Payment 잔액 이동 (구매자 → 판매자). 잔액 부족·계좌 없음이면 422, 상태 그대로 |
| PAYMENT_REQUESTED | `POST /trades/{id}/cancel` | 구매자·판매자 | CANCELED | Payment 주문 취소(CANCELED), Market 예약 해제(ON_SALE) |
| PAID | `POST /trades/{id}/complete` | 구매자 | COMPLETED | Market 판매 완료(SOLD) |
| PAID | `POST /trades/{id}/cancel` | 구매자·판매자 | CANCELED | Payment 환불(REFUNDED, 판매자 → 구매자), Market 예약 해제(ON_SALE) |
| COMPLETED · CANCELED | 없음 | — | (끝) | 다른 상태로 가는 요청 409, 같은 요청 재전송 200 |

- 구매 확정(COMPLETED) 전까지는 취소할 수 있습니다. 이미 결제했다면 Payment가 잔액을 구매자에게 돌려줍니다.
- 결제 요청 도중 Market 예약이나 Payment 주문 생성이 실패하면, 앞에서 한 일을 거꾸로 되돌리고 거래를 CANCELED(사유: 실패 단계)로 남깁니다.
- 결제의 멱등 키는 `trade-{거래번호}-pay`로 고정입니다. 응답을 못 받고 다시 눌러도 잔액은 한 번만 빠집니다.
- 결제 대기 중에 취소했는데 Payment 주문이 이미 결제돼 있으면(응답 유실 등) 주문 취소 대신 환불로 처리합니다.

## API

| 기능 | 메서드 · 주소 | 누가 | 요청 본문 | 성공 | 주요 실패 |
| --- | --- | --- | --- | --- | --- |
| 결제 요청 (거래 생성) | `POST /trades` (+ `Idempotency-Key`) | 판매자 | `{productId, buyerId, chatRoomId}` | 201 (같은 키 재시도는 200) | 403 ROLE_NOT_ALLOWED, 404 PRODUCT_NOT_FOUND, 409 PRODUCT_NOT_AVAILABLE, 422 SELF_PURCHASE, 502 |
| 거래 조회 | `GET /trades/{id}` | 당사자 | — | 200 | 403 NOT_TRADE_PARTICIPANT, 404 TRADE_NOT_FOUND |
| 내 거래 목록 | `GET /trades?role=&status=&productId=&chatRoomId=` | 본인 | — | 200 | 400 |
| 결제하기 | `POST /trades/{id}/pay` | 구매자 | — | 200 | 403 ROLE_NOT_ALLOWED, 409, 422 PAYMENT_REJECTED, 502 |
| 구매 확정 | `POST /trades/{id}/complete` | 구매자 | — | 200 | 403 ROLE_NOT_ALLOWED, 409, 502 |
| 거래 취소 | `POST /trades/{id}/cancel` | 당사자 | `{reason}` (선택) | 200 | 409 INVALID_STATUS, 502 |

모든 요청에 `X-Member-Id` 헤더(회원 번호)가 필요합니다. 금액·상품 제목·판매자는 요청으로 받지 않고 Market에서 가져와 스냅샷으로 저장합니다.
오류는 모두 ProblemDetail(`application/problem+json`) + `code` 필드입니다. 다른 서버가 응답하지 않으면 502 `UPSTREAM_UNAVAILABLE`입니다.

### HATEOAS (`_links`)

| 상태 | 구매자 | 판매자 |
| --- | --- | --- |
| PAYMENT_REQUESTED | self, product, chat, payment, pay, cancel | self, product, chat, payment, cancel |
| PAID | self, product, chat, payment, complete, cancel | self, product, chat, payment, cancel |
| COMPLETED | self, product, payment | self, product, payment |
| CANCELED | self, product | self, product |

`product`·`chat`·`payment`는 다른 서버의 자원입니다.

## Trade가 부르는 다른 서버 API

| 서버 | 메서드 · 주소 | 요청 | 기대 응답 |
| --- | --- | --- | --- |
| Market | `GET /products/{id}` | — | `{productId, title, price, sellerId, status, ...}` (status: ON_SALE / RESERVED / SOLD) |
| Market | `POST /products/{id}/reservation` | `{tradeId}` | 200 (이미 예약이면 409) |
| Market | `DELETE /products/{id}/reservation` | `{tradeId}` | 200 |
| Market | `POST /products/{id}/complete` | `{tradeId}` | 200 |
| Payment | `POST /orders` | `{sellerId, amount}` | 201 `{orderId, status: "PENDING"}` — orderId를 거래의 paymentId로 저장 |
| Payment | `POST /orders/{paymentId}/payments` | `{buyerId, idempotencyKey}` | 200 `{status: "COMPLETED"}` (잔액 부족 400 → Trade 422). 계좌가 없으면 자동 개설 |
| Payment | `POST /orders/{paymentId}/cancel` | — | 200 `{status: "CANCELED"}` (이미 결제됐으면 409 → 환불로 전환) |
| Payment | `POST /payments/{paymentId}/refund` | — | 200 `{status: "REFUNDED"}` |

## DB 스키마 (trade_db)

`src/main/resources/schema.sql` — 서버가 시작할 때 자동 실행됩니다 (`CREATE TABLE IF NOT EXISTS`).

- `trades` — 거래. 상품 제목·가격은 결제 요청 시점의 스냅샷, `chat_room_id`는 결제 요청을 보낸 채팅방, `payment_id`는 Payment 주문 번호. `active_product_id` UNIQUE로 한 상품에 진행 중인 거래는 하나뿐
- `trade_idempotency_keys` — 결제 요청 멱등 키
- `trade_status_histories` — 상태 변경 이력

## 실행

### 한 PC에서 전부 실행 (권장)

저장소 5개(`market-service`, `chat-service`, `trade-service`, `payment-service`, `won-pick-web`)가 같은 폴더에 있으면 됩니다. 없는 저장소는 스크립트가 clone 합니다.
MySQL 8(`root` / `1234`)과 Node.js 20 이상이 필요합니다.

```
scripts\run-all.cmd            # 이 PC에서만 시연 → http://localhost:5173
scripts\run-all.cmd -Pull      # 시작 전에 모든 저장소 git pull
scripts\run-all.cmd -Share     # 강의장 다른 PC도 http://<내 IP>:5173 으로 접속
scripts\run-all.cmd -SharedMarketDb   # Market DB로 팀 공용 DB(172.21.33.36) 사용
scripts\stop-all.cmd           # 모두 끄기 (5173·8080·8082·8083·8084)
```

- 서버마다 PowerShell 창이 하나씩 열립니다. 포트를 이미 쓰는 서버(IntelliJ에서 켠 서버 등)는 건너뜁니다.
- 각 서버의 DB 주소·계정은 스크립트가 환경 변수로 넣습니다. 다른 저장소의 설정 파일은 고치지 않습니다.
- Market은 기본으로 이 PC의 `wonpick_market`(자동 생성·초기 데이터)을 씁니다. `-SharedMarketDb`는 팀 공용 DB에 붙고, 접속이 안 되면 로컬로 돌아갑니다. 공용 DB에 `product_reservations` 테이블이 있어야 결제 요청(상품 예약)이 됩니다.
- Payment는 `payment_db` 테이블을 스스로 만들고, 처음 결제하는 회원에게 **1,000,000원짜리 가상 계좌**를 자동으로 열어 줍니다. 잔액 조회 `GET /accounts/{회원번호}`, 충전 `POST /accounts/{회원번호}/charge`.
- `-Share`는 내 IP를 찾아 화면·CORS 주소에 넣고 `npm run dev -- --host`로 띄웁니다. 방화벽(5173·8082·8083)은 관리자 권한이면 자동으로 열고, 아니면 명령을 안내합니다.

### Trade만 실행

1. MySQL 실행 (기본: `root` / `1234`, 다르면 환경 변수 `DB_USERNAME`, `DB_PASSWORD`). `trade_db`는 자동 생성됩니다.
2. 다른 서버 주소는 환경 변수 `MARKET_URL`, `PAYMENT_URL`, `CHAT_URL`, 화면 주소(CORS)는 `ALLOWED_ORIGINS`(쉼표 구분)로 바꿉니다.
3. 다른 서버 없이 혼자 테스트하려면 Trade 서버 안의 가짜(`/stub/**`)를 씁니다.

   | 프로필 | Market | Payment | 언제 |
   | --- | --- | --- | --- |
   | (없음) | 실제 8082 | 실제 8084 | 모든 서버가 준비됐을 때 |
   | `stub-pay` | 실제 8082 | 가짜 | Payment 서버를 켜지 않을 때 |
   | `stub` | 가짜 | 가짜 | Trade 혼자 테스트 (`http/trade-flow.http`, 판매자 100번) |

   ```
   ./gradlew bootRun --args='--spring.profiles.active=stub'
   ```
4. Swagger UI: http://localhost:8083/swagger-ui.html
