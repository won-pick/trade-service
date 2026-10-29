# Service 커버리지 공유본

2026-10-29에 생성한 **일회성 스냅샷**입니다. 이후 코드 변경에 따라 자동 갱신되지 않습니다.

| 항목 | 결과 |
| --- | --- |
| 기준 코드 커밋 | [c7d2504](https://github.com/won-pick/trade-service/commit/c7d25040bc5500ec0bf152306c6abb9cdc383758) |
| 실행 명령 | `.\gradlew.bat serviceTest` |
| Service 단위 테스트 | 73개 통과, 실패·오류 0개 |
| 측정 대상 | com.wonpick.trade.service (전체 서버 커버리지 아님) |
| 라인 | 94 / 94 = 100% |
| 분기 | 60 / 60 = 100% |
| 측정 도구 | JaCoCo 0.8.14 |

## 팀원이 보는 방법

1. 저장소를 clone하거나 git pull로 최신 코드를 받습니다.
2. 이 폴더의 [index.html](index.html)을 탐색기에서 찾아 Chrome/Edge로 엽니다.
3. 패키지 → TradeService → 소스 코드 순서로 클릭하면 실행된 라인과 분기를 확인할 수 있습니다.

GitHub의 HTML 파일 링크는 웹페이지 실행 화면 대신 소스 코드로 표시됩니다.
HTML 화면은 내려받은 파일을 브라우저로 열어 확인하세요. 다운로드한 저장소에서 테스트를 실행할 필요는 없습니다.
HTML, CSS, JavaScript, 이미지 파일이 서로 연결되므로 이 폴더 전체를 함께 보관하세요.

[원본 XML 수치](service-coverage.xml)와 [테스트 학습 가이드](../../SERVICE-TEST-GUIDE.md)도 포함되어 있습니다.
최신 코드 기준으로 다시 측정하려면 저장소 최상위에서 위 실행 명령을 사용하세요.
새 결과는 build/reports/jacoco/service/html에 생성되며, 이 공유본을 자동으로 덮어쓰지 않습니다.

커버리지 100%는 해당 Service의 실행 범위를 뜻합니다.
실제 DB 갱신, 외부 결제 API, HTTP 응답, 동시 요청 등의 검증을 모두 보장하지는 않습니다.
