# WON-pick Trade 서버

우리WON 청년 IT 아카데미(A·B반) 교내 중고거래 서비스 **WON-pick**의 거래(Trade) 서버입니다.
거래 자원 하나의 상태를 책임지고, 구매 문의부터 구매 확정까지 Market·Payment·Chat 서버를 차례로 부르는 조율자입니다.

## 서버 구성도

```
                React (5173)
                    │  HTTP + JSON, X-Member-Id 헤더
                    ▼
        ┌───────────────────────┐
        │  Trade 서버 (8083)     │── trade_db
        └───────────────────────┘
          │          │          │
          ▼          ▼          ▼
   Market (8081)  Payment (8084)  Chat (8082)
   상품 조회·      송금 요청·       거래 채팅방·
   판매완료        입금 승인·취소    시스템 메시지
                     ┊
                     ▼
              카카오페이 송금 링크 (구매자가 앱에서 직접 송금)
```

| 서버 | 포트 | DB 스키마 |
| --- | --- | --- |
| Market | 8081 | (Market 담당) |
| Chat | 8082 | (Chat 담당) |
| **Trade** | **8083** | **trade_db** |
| Payment | 8084 | (Payment 담당) |

## 상태 전이표

| 지금 상태 | 행위 (주소) | 누가 | 다음 상태 | 함께 일어나는 일 |
| --- | --- | --- | --- | --- |
| (없음) | 구매 문의 `POST /trades` | 구매자 | REQUESTED | Market 상품 확인, Chat 채팅방 생성. 한 상품에 여러 구매자 가능 |
| REQUESTED | `POST /trades/{id}/request-payment` | 판매자 | PAYMENT_PENDING | Payment 송금 요청 생성. 같은 상품에 송금 단계 거래가 있으면 409 |
| REQUESTED | `POST /trades/{id}/cancel` | 구매자·판매자 | CANCELED | — |
| PAYMENT_PENDING | `POST /trades/{id}/confirm-payment` | 판매자 | PAID | Payment 승인 |
| PAYMENT_PENDING | `POST /trades/{id}/cancel` | 구매자·판매자 | CANCELED | Payment 취소 |
| PAID | `POST /trades/{id}/complete` | 구매자 | COMPLETED | Market 판매완료, 같은 상품의 다른 문의 자동 취소 |
| COMPLETED · CANCELED | 없음 | — | (끝) | 다른 상태로 가는 요청 409, 같은 요청 재전송 200 |

입금이 확인된(PAID) 뒤에는 취소할 수 없습니다. 직송금이라 서버가 돈을 되돌릴 수 없기 때문입니다.

## API

| 기능 | 메서드 · 주소 | 누가 | 성공 | 주요 실패 |
| --- | --- | --- | --- | --- |
| 구매 문의 | `POST /trades` (+ `Idempotency-Key`) | 구매자 | 201 (이미 있으면 200) | 404 PRODUCT_NOT_FOUND, 409 PRODUCT_NOT_AVAILABLE, 422 SELF_PURCHASE |
| 거래 조회 | `GET /trades/{id}` | 당사자 | 200 | 403 NOT_TRADE_PARTICIPANT, 404 TRADE_NOT_FOUND |
| 내 거래 목록 | `GET /trades?role=&status=&productId=` | 본인 | 200 | 400 |
| 송금 요청 | `POST /trades/{id}/request-payment` | 판매자 | 200 | 403 ROLE_NOT_ALLOWED, 409 |
| 입금 확인 | `POST /trades/{id}/confirm-payment` | 판매자 | 200 | 403 ROLE_NOT_ALLOWED, 409 |
| 구매 확정 | `POST /trades/{id}/complete` | 구매자 | 200 | 403 ROLE_NOT_ALLOWED, 409, 502 |
| 거래 취소 | `POST /trades/{id}/cancel` | 당사자 | 200 | 409 INVALID_STATUS |

모든 요청에 `X-Member-Id` 헤더가 필요합니다. 오류는 모두 ProblemDetail(`application/problem+json`) + `code` 필드입니다.
다른 서버가 응답하지 않으면 502 `UPSTREAM_UNAVAILABLE`입니다.

### HATEOAS (`_links`)

| 상태 | 구매자 | 판매자 |
| --- | --- | --- |
| REQUESTED | self, product, chat, cancel | self, product, chat, request-payment, cancel |
| PAYMENT_PENDING | self, product, chat, payment, transfer, cancel | self, product, chat, payment, confirm-payment, cancel |
| PAID | self, product, chat, payment, complete | self, product, chat, payment |
| COMPLETED | self, product, payment | self, product, payment |
| CANCELED | self, product | self, product |

`product`·`chat`·`payment`는 다른 서버의 자원, `transfer`는 판매자의 카카오페이 송금 링크입니다.

## Trade가 부르는 다른 서버 API

| 서버 | 메서드 · 주소 | 요청 | 기대 응답 |
| --- | --- | --- | --- |
| Market | `GET /products/{id}` | — | `{productId, title, price, sellerId, status}` (status: ON_SALE / SOLD) |
| Market | `POST /products/{id}/sold` | — | 200 |
| Payment | `POST /payments` + `Idempotency-Key` | `{tradeId, payerId, payeeId, amount, method: "KAKAOPAY_TRANSFER"}` | 201 `{paymentId, status: "READY", transferUrl}` |
| Payment | `POST /payments/{id}/approve` | — | 200 |
| Payment | `POST /payments/{id}/cancel` | — | 200 |
| Chat | `POST /chat-rooms` | `{tradeId, productId, buyerId, sellerId}` | 201 `{chatRoomId}` |
| Chat | `POST /chat-rooms/{id}/system-messages` (선택) | `{text}` | 201 |

## DB 스키마 (trade_db)

`src/main/resources/schema.sql` — 서버가 시작할 때 자동 실행됩니다 (`CREATE TABLE IF NOT EXISTS`).

- `trades` — 거래. 상품 제목·가격은 거래 시작 시점의 스냅샷. `active_product_id` UNIQUE로 한 상품에 송금 단계 거래는 하나뿐
- `trade_idempotency_keys` — 구매 문의 멱등 키
- `trade_status_histories` — 상태 변경 이력

## 실행

1. MySQL 실행 (기본: `root` / `1234`, 다르면 환경 변수 `DB_USERNAME`, `DB_PASSWORD`). `trade_db`는 자동 생성됩니다.
2. 다른 서버 주소는 `application.yml`의 `servers.*`. 아직 없으면 Apidog Mock 주소로 바꿉니다.
3. 혼자 테스트: **stub 프로필**로 실행하면 Trade 서버 안의 가짜 Market·Chat·Payment(`/stub/**`)를 부릅니다.
   ```
   ./gradlew bootRun --args='--spring.profiles.active=stub'
   ```
   그다음 `http/trade-flow.http`를 위에서부터 실행합니다.
4. Swagger UI: http://localhost:8083/swagger-ui.html
