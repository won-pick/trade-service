# WON-pick Trade 서버

판매자의 결제 요청부터 구매자의 모의 결제·구매 확정까지 Market, Chat, Payment를 연결합니다.
외부 카카오페이 API는 사용하지 않고 Payment의 가상 잔액 결제를 사용합니다.

## 분산 PC 실행

전체 주소·DB·CORS 설정과 서버 간 요청 형식은 [통합 규격](docs/TEAM-INTEGRATION.md)에 있습니다.
각 PC에서 담당 서버만 실행합니다. Trade 기본 포트는 8083, Market 8082, Chat 8080, Payment 8084입니다.
Trade PC의 MARKET_SERVER_URL, CHAT_SERVER_URL, PAYMENT_SERVER_URL에 각 담당 PC의 실제 주소를 설정하세요.
프론트의 실제 Origin은 TRADE_CORS_ALLOWED_ORIGINS에 지정합니다.
기본 DB는 담당 PC의 localhost:3306/trade_db, 계정은 root / 1234입니다.
MySQL 실행 후 서버를 시작하면 DB와 없는 테이블을 자동 생성합니다.
연결 정보를 바꿀 때만 TRADE_DB_URL, DB_USERNAME, DB_PASSWORD를 지정하세요.

    .\gradlew.bat bootRun

## API

모든 요청에 X-Member-Id 헤더가 필요합니다.

| 메서드·주소 | 요청자 / 본문 | 결과 |
| --- | --- | --- |
| POST /trades | 판매자, {productId,buyerId,chatRoomId}, Idempotency-Key 권장 | 예약·주문 생성, PAYMENT_REQUESTED. 신규 201, 기존 200 |
| GET /trades | 당사자. role/status/productId/chatRoomId 필터 | {trades,_links} |
| GET /trades/{id} | 당사자 | 거래와 역할별 실행 링크 |
| POST /trades/{id}/request-payment | 판매자 | REQUESTED에서 중단된 결제 요청 재개 |
| POST /trades/{id}/pay | 구매자 | 모의 결제, PAID |
| POST /trades/{id}/complete | 구매자 | 구매 확정, COMPLETED, Market SOLD |
| POST /trades/{id}/cancel | 당사자, 선택 {reason} | 취소·환불, CANCELED, Market 예약 해제 |

REQUESTED는 준비 상태입니다. 연결 실패 후에도 재시도 또는 취소할 수 있도록 거래 번호를 보존합니다.
PAYMENT_REQUESTED의 구매자에게는 pay와 cancel, 판매자에게는 cancel을 제공합니다.
PAID의 구매자에게는 complete와 cancel, 판매자에게는 cancel을 제공합니다. 구매 확정 전 취소 시 환불합니다.
프론트는 _links에 있는 행동만 표시합니다.

현재 배포는 Trade 한 인스턴스이며 변경 요청을 직렬화합니다.
Payment 주문·결제는 거래 번호 기반 멱등 키로 재시도합니다.
금액·판매자는 Market 조회값으로 결정하고 기존 채팅방의 상품·참여자를 확인합니다.

## 검증

    .\gradlew.bat test

테스트는 독립 H2와 로컬 HTTP 계약 서버를 사용하며 운영 DB에 접근하지 않습니다.
실제 팀 PC 연결은 통합 규격의 확인 시나리오로 검증하세요.
Swagger 경로는 /swagger-ui.html입니다.

stub 프로필은 규격 확인용 가짜 응답입니다. 실제 서버 연동 때는 활성화하지 않습니다.
stub에서도 Trade DB는 필요합니다. 가짜 채팅방 번호는 상품 번호와 같고 판매자 100, 구매자 3입니다.
[HTTP 예시](http/trade-flow.http)의 주소·ID·키를 자신의 환경에 맞춰 실행하세요.
