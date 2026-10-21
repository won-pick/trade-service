# WON-pick 분산 PC 연동 규격

각 담당 PC에서 해당 서버만 실행합니다. 아래 <..._PC_IP>는 실제 LAN IP로 바꾸세요.

## 주소 설정

| 실행 위치 | 환경 변수 | 값 |
| --- | --- | --- |
| 프론트 PC의 .env | VITE_MARKET_URL | `http://<MARKET_PC_IP>:8082` |
| 프론트 PC의 .env | VITE_TRADE_URL | `http://<TRADE_PC_IP>:8083` |
| 프론트 PC의 .env | CHAT_SERVER_URL | `http://<CHAT_PC_IP>:8080` |
| Trade PC | MARKET_SERVER_URL | `http://<MARKET_PC_IP>:8082` |
| Trade PC | CHAT_SERVER_URL | `http://<CHAT_PC_IP>:8080` |
| Trade PC | PAYMENT_SERVER_URL | `http://<PAYMENT_PC_IP>:8084` |
| Market PC | TRADE_BASE_URL | `http://<TRADE_PC_IP>:8083` |
| Market PC | MARKET_CORS_ALLOWED_ORIGINS | 실제 프론트 Origin |
| Trade PC | TRADE_CORS_ALLOWED_ORIGINS | 실제 프론트 Origin |

프론트 PC의 브라우저에서 접속한다면 Origin은 http://localhost:5173입니다.
다른 PC에서도 프론트를 열려면 .env에 FRONTEND_HOST=0.0.0.0을 설정하고,
Market/Trade 양쪽 CORS에 `http://<FRONTEND_PC_IP>:5173`을 넣습니다.
여러 Origin은 쉼표로 구분합니다. 서버 간 HTTP 요청은 CORS 대상이 아닙니다.
각 PC 방화벽은 해당 서비스 포트의 팀 네트워크 접근을 허용해야 합니다.

Spring 서버는 환경 변수를 설정한 터미널 또는 IDE 실행 환경에서 실행하세요.
예를 들어 Trade PC의 PowerShell:

    $env:MARKET_SERVER_URL='http://<MARKET_PC_IP>:8082'
    $env:CHAT_SERVER_URL='http://<CHAT_PC_IP>:8080'
    $env:PAYMENT_SERVER_URL='http://<PAYMENT_PC_IP>:8084'
    $env:TRADE_CORS_ALLOWED_ORIGINS='http://localhost:5173,http://<FRONTEND_PC_IP>:5173'
    .\gradlew.bat bootRun

DB 설정은 각 서비스가 소유하며 기존 DB 위치·계정은 그대로 두었습니다.
- Market: MARKET_DB_URL, MARKET_DB_USERNAME, MARKET_DB_PASSWORD. 기본 공유 DB의 테이블은 사전 생성되어 있어야 합니다.
- Trade: TRADE_DB_URL, DB_USERNAME, DB_PASSWORD.
- Payment: PAYMENT_DB_URL, PAYMENT_DB_USERNAME, PAYMENT_DB_PASSWORD.
- Chat: 기본 H2는 재시작하면 초기화됩니다. 반복 통합 시연에는 mysql 프로필과 CHAT_DB_URL, CHAT_DB_USER, CHAT_DB_PASSWORD를 사용하세요.

프론트는 won-pick-web/.env.example을 .env로 복사해 실제 IP를 채우고 npm ci, npm run dev로 실행합니다.
.env 변경 후에는 Vite를 재시작해야 합니다. /chat-api 프록시는 Vite에서 Chat PC로 전달합니다.
정적 배포 시에는 별도의 /chat-api 역방향 프록시가 필요합니다.

## 확정한 흐름

1. 프론트 → Market: 회원·상품 생성 및 조회.
2. 프론트 → Chat: POST /chat/rooms에 {productId,buyerId,sellerId}. 응답의 id가 채팅방 번호.
3. 판매자 → Trade: POST /trades, X-Member-Id: 판매자ID, Idempotency-Key, 본문 {productId,buyerId,chatRoomId}.
4. Trade는 Market의 판매자·가격과 Chat의 기존 방 참여자를 확인하고 상품 예약과 Payment 주문 생성을 수행.
5. 구매자 → Trade: 응답의 _links.pay에 POST. Payment의 가상 잔액이 구매자에서 판매자로 이동.
6. 구매자 → Trade: _links.complete에 POST. Market의 예약 상품을 판매 완료로 전환.

실제 카카오페이 API, 결제창, 송금 URL은 사용하지 않습니다.
Payment의 payment.initial-balance 기본값은 실습용 1,000,000원입니다.
무료 상품은 채팅으로 합의하며 모의 결제 요청 금액은 0원보다 커야 합니다.

| Trade 상태 | 의미 | 구매자 행동 | 판매자 행동 |
| --- | --- | --- | --- |
| REQUESTED | 예약·주문 생성 준비 또는 재시도 대기 | cancel | request-payment, cancel |
| PAYMENT_REQUESTED | 결제 대기 | pay, cancel | cancel |
| PAID | 가상 잔액 결제 완료 | complete, cancel | cancel |
| COMPLETED | 구매 확정·판매 완료 | 없음 | 없음 |
| CANCELED | 결제 전 취소 | 없음 | 없음 |

Trade 취소는 원격 저장소의 정책대로 구매 확정 전까지 허용하며, 결제 완료 건은 환불합니다. 결제 성공 응답이 유실되어 Trade가 결제 대기로 남은 경우,
취소 과정에서 Payment의 실제 완료 상태를 확인하고 환불한 뒤 상품 예약을 해제합니다.
모든 거래 변경에는 X-Member-Id가 필요하며 다른 참여자의 역할로 실행하면 403입니다.

## 서버 간 계약

| 호출 | 본문 / 응답 |
| --- | --- |
| Trade → Market GET /products/{id} | {productId,title,price,sellerId,status,...} |
| Trade → Market POST /products/{id}/reservation | {tradeId} |
| Trade → Market DELETE /products/{id}/reservation | {tradeId} (DELETE에도 JSON 본문) |
| Trade → Market POST /products/{id}/complete | {tradeId} |
| Trade → Chat GET /chat/rooms/{id}?memberId=판매자ID | {id,productId,buyerId,sellerId} |
| Trade → Payment POST /orders | {sellerId,amount}, 헤더 Idempotency-Key: trade-{id}-order; 응답 {orderId,status,...} |
| Trade → Payment POST /orders/{orderId}/payments | {buyerId,idempotencyKey:"trade-{id}-pay"}; 응답 {paymentId,status:"COMPLETED",...} |
| Trade → Payment GET /payments/{orderId} | 실제 결제 상태 확인 (orderId와 paymentId는 같은 행의 번호) |
| Trade → Payment POST /orders/{orderId}/cancel | 결제 전 주문 취소 |
| Trade → Payment POST /payments/{paymentId}/refund | 결제 응답 유실 후 취소 시 환불 |

GET /trades?chatRoomId=...는 해당 방의 거래만 최신순으로 반환합니다.
_links.chat은 /chat/rooms/{id}?memberId=조회자ID이며 프론트가 /chat-api로 변환합니다.
거래 알림은 프론트가 Chat의 일반 메시지 API에 멱등 키와 함께 전송합니다.

## 재시도와 DB 변경

- Trade 생성 키는 같은 판매자·상품·구매자·채팅방에만 재사용할 수 있습니다.
- Payment 주문 생성 키는 payment_order_keys에 저장합니다. 같은 키·내용은 같은 주문을 반환하고 내용이 다르면 409입니다.
- payment_order_keys는 Payment의 schema.sql이 시작 시 추가합니다. 기존 테이블 컬럼 변경과 데이터 삭제는 없습니다.
- Market 예약 해제는 예약 기록이 없더라도 해제 기록을 남깁니다. 늦게 도착한 같은 거래의 예약은 거절하고 다른 거래의 예약은 건드리지 않습니다.
- 예약/주문 응답 유실 시 Trade는 REQUESTED를 남깁니다. 판매자는 같은 생성 요청 또는 _links.request-payment로 재개하거나 취소할 수 있습니다.
- 각 서비스 한 인스턴스 기준입니다. Trade 변경 요청은 서버 내에서 직렬화합니다. 여러 Trade 인스턴스로 확장할 때는 DB 기반 작업 잠금이 필요합니다.
- 각 DB의 ID는 연동에 사용되므로 한 서비스 DB만 초기화해 기존 ID를 재사용하지 마세요.

## 팀 확인 시나리오

판매자와 구매자를 서로 다른 브라우저 세션으로 열어 상품 등록 → 채팅 → 판매자 결제 요청 → 구매자 결제 → 구매 확정을 확인합니다.
별도 상품으로 결제 전 취소도 확인합니다. 구매 확정 후 Market은 SOLD, 취소 후 ON_SALE이어야 합니다.
실제 팀 PC 간 연결은 각 PC의 IP, DB 접속, 방화벽이 준비된 뒤 확인해야 합니다.

## 검증 결과 (2026-10-21)

- Trade 11개, Market 24개, Chat 8개, Payment 5개 테스트 통과 (총 48개).
- 프론트 npm run build 통과.
- 실제 서버 4개와 Vite를 임시 포트·독립 H2 메모리 DB로 실행한 HTTP 통합 검증 통과.
- 확인 항목: 회원·상품 생성, Vite를 통한 채팅방·메시지 생성, 결제 요청 재시도, 채팅방별 거래 조회, 구매자 결제 재시도 시 잔액 1회 차감, 구매 확정 시 SOLD, 결제 전 취소 시 ON_SALE.
- 검증용 프로세스는 종료했습니다. 팀 PC 네트워크와 운영 MySQL 연결은 이 검증에 포함하지 않았습니다.


각 저장소의 Gradle test 또는 프론트 npm run build로 검증할 수 있습니다. 한 PC 테스트 실행 스크립트는 별도의 로컬 작업 폴더 도구이며 이 배포 설정에 포함하지 않습니다.
