# TradeService 단위 테스트 읽기

이번 대상은 이 저장소의 `TradeService`다. 다른 팀 서버의 소스는 수정하지 않는다.
Controller 단위 테스트 추가는 다음 단계이며, 기존 HTTP 계약 테스트는 그대로 유지한다.

## 1. 실제 객체와 Mock 구분

| 구성 요소 | 테스트에서의 사용 | 이유 |
| --- | --- | --- |
| TradeService | 실제 객체 | 이 객체의 분기·역할 검사·업무 실행 순서를 검증한다. |
| Trade / TradeStatus / MarketProduct | 실제 객체 | 실제 값과 상태 규칙으로 판단하도록 한다. |
| TradeRepository / IdempotencyKeyRepository | Mockito Mock | MySQL 연결 없이 조회 결과와 저장 성공·실패를 설정한다. |
| MarketClient / PaymentClient / ChatClient | Mockito Mock | 실제 외부 서버·결제 API를 호출하지 않는다. |
| TransactionTemplate | Service 내부의 실제 객체 | 트랜잭션 콜백 안의 저장 로직도 실제 실행해야 한다. |
| PlatformTransactionManager | Mockito Mock | 실제 DB 연결 대신 트랜잭션 시작·커밋·롤백 요청을 관찰한다. |

테스트 파일은 `src/test/java/com/wonpick/trade/service/TradeServiceTest.java`다.
`@SpringBootTest` 대신 `@ExtendWith(MockitoExtension.class)`를 사용한다.
각 테스트는 새로운 Mock을 사용하며 서버를 띄우지 않는다.
운영 코드의 생성자에 Mock을 전달할 뿐, 테스트를 위해 Service 로직을 변경하지 않았다.

## 2. Given–When–Then과 BDDMockito

예를 들어 `PayTests.rejectedPaymentDoesNotSavePaidState`는 다음 요구사항을 검사한다.

> 구매자의 결제가 거절되면 예외를 전달하고, 거래를 PAID로 저장하거나 결제 완료 이력을 남기지 않는다.

```java
// Given: 결제 대기 거래가 있고, PaymentClient는 잔액 부족 예외를 던진다.
given(trades.findById(ID)).willReturn(Optional.of(before));
willThrow(failure).given(payment).pay(before);

// When / Then: 실제 Service 메서드를 실행해서 예외가 발생하는지 확인한다.
assertThatThrownBy(() -> service.pay(ID, BUYER)).isSameAs(failure);

// Then: 예외뿐 아니라 상태 저장·이력 저장이 없다는 것까지 검증한다.
then(trades).should(never()).toPaid(anyLong());
then(trades).should(never()).insertHistory(anyLong(), any(), any(), anyLong());
```

- `given(mock.method()).willReturn(value)`: 의존성을 호출했을 때 돌려줄 값을 준비한다.
- `given(mock.method()).willThrow(exception)`: 반환형이 있는 메서드의 실패를 준비한다.
- `willThrow(exception).given(mock).voidMethod()`: void 메서드의 실패를 준비한다.
- `then(mock).should().method()`: 해당 메서드가 기본 1회 호출됐는지 확인한다.
- `then(mock).should(never()).method()`: 호출되면 안 되는 작업이 실행되지 않았는지 확인한다.
- `then(mock).shouldHaveNoInteractions()`: 그 의존성에 아무 요청도 보내지 않았는지 확인한다.
- `then(mock).should(inOrder(...))`: 결제 성공 후 저장 등, 업무상 필요한 실행 순서를 확인한다.
- `assertThat(...)`: 실제 Service 반환값의 상태·역할·금액 등을 비교한다.

예외 테스트에서는 `When`에서 값을 반환받을 수 없으므로 `assertThatThrownBy` 안의 람다가 실제 실행 구간이다.
주석이 세 개 있는지만 보지 말고, 그 람다에 검사할 Service 메서드가 들어 있는지 확인한다.

## 3. 요구사항과 테스트 연결

파일 안의 `@Nested` 그룹별로 읽으면 된다. 파라미터화 테스트는 각 입력값을 독립 실행한다.

| 요구사항 | 대표 테스트 | 반환값·예외 검증 | 저장·외부 호출 검증 |
| --- | --- | --- | --- |
| 판매자가 상품 정보로 거래 생성 | `CreateTests.createsTradeUsingMarketSnapshot` | 신규 여부, 결제 대기, 금액·참여자 | Market 조회값 저장, 방 연결, 키·이력 저장, 예약 후 주문 생성 |
| 금액 경계 | `createsWithoutKeyAtPriceBoundaries`, `rejectsNonPositivePrice` | 1원·Market 최대 가격 허용, 0원·음수 거절 | 정확한 가격 저장 또는 거래 저장·결제 없음 |
| 같은 키의 재요청은 중복 생성 금지 | `replaysExistingKeyWithoutRepeatingSideEffects` | 기존 거래 반환, created=false | 새 거래·주문·외부 요청 없음 |
| 같은 키를 다른 요청에 재사용 금지 | `rejectsKeyReusedForDifferentRequest` | 판매자·상품·구매자·방 각각 변경 시 INVALID_STATUS | 키 추가 저장·외부 요청 없음 |
| 중단된 요청 재개 | `resumesPreparingTradeFromKey` | 기존 거래 ID로 결제 대기 반환 | 거래 재삽입 없이 예약·주문 재개 |
| 열린 거래 재사용 | `reusesOpenTradeAndAssociatesNewKey`, `reusesOpenTradeWithoutKey` | 기존 거래 반환 | 새 키가 있으면 연결, 주문 재생성 없음 |
| 판매자·자기 구매·채팅방 검사 | `rejectsNonOwnerBeforeCheckingRoom`, `rejectsSelfPurchase`, `stopsWhenRoomValidationFails` | ROLE_NOT_ALLOWED / SELF_PURCHASE / 전달된 예외 | 거래 저장·주문 생성 없음 |
| 거래 조회 권한 | `ReadTests.participantCanRead`, `strangerCannotRead`, `missingTradeIsRejected` | 당사자 허용, NOT_TRADE_PARTICIPANT / TRADE_NOT_FOUND | 외부 API 호출 없음 |
| 목록 조회 | `forwardsListFilters`, `returnsEmptyListWithoutFilters` | 거래 목록 또는 빈 목록 | 조회자와 필터가 정확히 Repository에 전달됨 |
| 예약 확보 뒤 주문 생성 | `RequestPaymentTests.preparesPaymentInBusinessOrder` | 결제 대기 반환 | 상품 확보 → Market 예약 → Payment 주문 → 상태·이력 저장 순서 |
| 상품 중복 확보 거절 | `rejectsClaimConflict` | PRODUCT_NOT_AVAILABLE | Market·Payment 호출 없음 |
| 외부 예약·주문 실패 | `reservationFailureStopsOrderCreation`, `orderFailureDoesNotAdvanceOrReleaseReservation` | 전달된 예외 | 결제 대기 저장·이력 없음; 응답 유실 가능성이 있어 예약을 임의 해제하지 않음 |
| 구매자만 결제 | `PayTests.rejectsWrongPayer`, `rejectsInvalidState` | 역할·참여자·상태 오류 | Payment 결제·PAID 저장 없음 |
| 결제 성공 후 상태 저장 | `paysBeforeRecordingPaidState` | PAID 반환 | Payment → PAID → 구매자 이력 순서 |
| 결제 거절 | `rejectedPaymentDoesNotSavePaidState` | 같은 예외와 잔액 부족 메시지 | PAID·이력 저장 없음 |
| 구매 확정 | `CompleteTests.completesProductBeforeTrade`, `marketFailureDoesNotSaveCompletion` | COMPLETED 또는 전달된 예외 | Market 판매 완료 뒤 상태·이력 저장; 실패하면 저장 없음 |
| 결제 전후 취소 | `CancelTests.cancelsPaymentBeforeReleasingProduct` | 취소 상태·사유·요청자 | Payment 정리 → Market 예약 해제 → 상태·이력 저장 순서 |
| 주문 번호 응답 유실 후 취소 | `resolvesMissingOrderBeforeCanceling` | CANCELED 반환 | 멱등 주문 조회/생성 요청 뒤 취소 요청 |
| 취소·환불 실패 | `paymentFailureDoesNotReleaseOrSaveCancellation`, `releaseFailureDoesNotSaveCancellation` | 전달된 예외 | 결제 실패 시 예약 해제 없음; 예약 해제 실패 시 취소 저장 없음 |
| 이미 처리한 상태의 재요청 | 각 그룹의 `already...IsIdempotent` | 기존 거래 반환 | 결제·판매 완료·환불 중복 호출 없음 |
| DB 변경 0행이면 이력 없음 | 각 변경 그룹의 `zeroUpdatedRowsDoNotProduceHistory` | 다시 조회한 현재 상태 | 변경 이력 추가 없음 |
| 생성 중 DB 오류 | `requestsRollbackWhenCreationPersistenceFails` | 원래 DB 예외 전달 | 관리자 rollback 요청, commit·예약·결제 없음 |

## 4. 실행 방법

이 저장소의 최상위 폴더에서 PowerShell로 실행한다. MySQL과 다른 서버는 필요 없다.

```powershell
# Service 단위 테스트 실행 + Service 커버리지 HTML/XML 자동 생성
.\gradlew.bat serviceTest

# 기존 HTTP 계약·domain 테스트까지 전체 실행
.\gradlew.bat test

# 전체 테스트 실행 결과로 전체 프로젝트 커버리지 생성
.\gradlew.bat test jacocoTestReport
```

JUnit Jupiter·Mockito·AssertJ는 기존 `spring-boot-starter-test`에 들어 있다.
추가한 것은 Gradle의 `jacoco` 플러그인과 측정 도구 버전 설정이다.
JaCoCo를 노트북에 별도 프로그램으로 설치할 필요 없이 Gradle이 내려받는다.

| 리포트 | 경로 | 의미 |
| --- | --- | --- |
| Service 테스트 결과 | `build/reports/tests/serviceTest/index.html` | 실패한 테스트와 상세 원인 |
| Service 커버리지 | `build/reports/jacoco/service/html/index.html` | Service 단위 테스트만으로 측정한 Service 패키지 |
| Service 커버리지 XML | `build/reports/jacoco/service/service.xml` | 라인·분기 집계 수치 |
| 전체 테스트 결과 | `build/reports/tests/test/index.html` | 기존 테스트와 새 테스트 전체 |
| 전체 커버리지 | `build/reports/jacoco/test/html/index.html` | 전체 테스트로 측정한 전체 운영 코드 |

`serviceTest`와 `test`는 각각 다른 JaCoCo 실행 데이터 파일을 사용한다.
Service 리포트에 기존 HTTP 계약 테스트의 실행 결과를 합치지 않는다.
빌드 결과는 `.gitignore`의 `build/` 규칙으로 Git에서 제외된다.
테스트 실행 없이 결과를 보려면 [2026-10-29 공유용 리포트](coverage/service/README.md)를 확인한다.
이 공유본은 생성 시점의 스냅샷이며 자동 갱신되지 않는다.

### 실행 확인 결과 (2026-10-29)

- `serviceTest`: 파라미터별 실행을 포함한 **73개 통과**, 실패·오류·건너뜀 0개.
- `test jacocoTestReport`: 새 단위 테스트와 기존 테스트를 합친 **84개 통과**, 실패·오류·건너뜀 0개.

| 측정 범위 | 사용한 테스트 | 라인 커버리지 | 분기 커버리지 |
| --- | --- | --- | --- |
| Service 패키지 | serviceTest | 94 / 94 = 100% | 60 / 60 = 100% |
| 전체 운영 코드 | test (기존 테스트 포함) | 413 / 493 = 83.77% | 148 / 199 = 74.37% |

수치는 생성된 두 XML 리포트의 LINE / BRANCH 카운터에서 확인했다.
이 차이를 통해 Service의 100%를 전체 서버의 100%로 표현하면 안 되는 이유를 확인할 수 있다.

## 5. 라인 커버리지와 분기 커버리지

- **라인 커버리지:** 실행 가능한 소스 라인 중 한 번이라도 실행한 라인의 비율이다.
- **분기 커버리지:** if/switch 등에서 가능한 분기 중 실행한 분기의 비율이다.

예를 들어 `if (trade.status() == PAID) return trade;`를 PAID인 거래로만 실행하면
해당 조건 라인은 실행되지만, PAID가 아닌 경우의 흐름은 검사하지 못한다.
정상·오류·경계 조건을 함께 작성해야 분기 누락을 찾을 수 있다.
`A || B` 같은 복합 조건은 단락 평가 때문에 A와 B를 독립적으로 바꾸는 사례가 필요할 수 있다.

커버리지는 얼마나 실행했는지를 나타내며, 기대값이 올바른지와 요구사항을 모두 검증했는지는 보장하지 않는다.
예외 처리와 네트워크 실패 같은 사례도 수치만 보고 생략하면 안 된다.
특히 JaCoCo의 분기 집계는 예외 처리 자체를 별도의 분기로 세지 않는다.
수치를 맞추기 위한 임의의 합격 기준이나 운영 코드 제외 규칙은 추가하지 않았다.
Service 전용 리포트는 처음부터 대상 패키지를 한정한 학습용 측정이며 전체 서버 수치가 아니다.

공식 설명: [JaCoCo Coverage Counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html),
[사용한 버전의 변경 내역](https://www.jacoco.org/jacoco/trunk/doc/changes.html).

## 6. 이번 단위 테스트가 보장하지 않는 것

- Mock Repository가 반환한 거래는 테스트가 준비한 값이다. 실제 MySQL에서 행이 갱신됐다는 증거는 아니다.
- PlatformTransactionManager의 rollback 호출은 확인하지만 실제 DB가 롤백됐는지는 별도 DB 테스트가 필요하다.
- PaymentClient의 `cancelOrRefund` 호출은 확인하지만, 실제 잔액 이동과 HTTP 경로는 이 테스트에서 확인하지 않는다.
- HTTP 상태 코드, 요청 DTO 검증, JSON 응답 형식은 Controller 테스트의 책임이다.
- 여러 요청의 동시 실행, 프로세스 재시작, 네트워크 응답 유실, 여러 서버의 부분 성공은 별도 통합 검증 대상이다.
- Service 현재 구현은 조건부 UPDATE 0행에서 예외를 던지지 않고 재조회 결과를 반환한다. 테스트에 없는 새 오류 정책을 만들어 넣지 않았다.

공부할 때는 정상 결제 테스트와 실패 결제 테스트를 나란히 읽고,
Then의 저장 호출 검증 한 줄을 제거하면 어떤 버그를 놓칠 수 있을지 생각해 보는 것이 좋다.
