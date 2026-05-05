# ADR 013: Coverage reporting과 테스트 우선순위

## 상태

채택

## 배경

테스트 수가 늘어도 coverage를 재현 가능하게 측정하지 못하면 개선 폭을 객관적으로 확인하기 어렵다.
또한 이 프로젝트는 DTO/record보다 service, processor, scheduler, configuration 경계에 분기와 운영 semantics가 몰려 있다.

## 결정

- Gradle build에 `jacoco` plugin을 추가한다.
- `./gradlew test` 실행 후 `jacocoTestReport`를 자동 생성한다.
- coverage report는 XML과 HTML 둘 다 남긴다.
- 기본 확인 경로는 `build/reports/jacoco/test/html/index.html`, `build/reports/jacoco/test/jacocoTestReport.xml`이다.
- coverage 보강 우선순위는 `ImageJobCommandService`, `ImageJobQueryService`, `JobProcessor`, `JobRecoveryService`, `JobCleanupScheduler`, `JobProcessingScheduler`, configuration bean 순서로 둔다.
- `RestWorkerClient`는 별도 우선순위 대상으로 두고 API key issuance, cached key reuse, `401` refresh, invalid payload, 4xx/5xx/timeout mapping 분기를 직접 테스트한다.
- `JobProcessor`는 long-poll loop 가 아니라 single-step start/status poll, released lease continuation, interrupt recovery 분기를 직접 테스트한다.
- private nested record 같은 저신호 boilerplate보다 사용자 관찰 가능 분기와 운영 semantics가 있는 코드 경계를 먼저 테스트한다.

## 이유

- JaCoCo는 Gradle과 통합이 단순하고 CI/로컬 모두에서 재현 가능하다.
- XML report는 향후 CI 연계나 quality gate에 쓰기 쉽다.
- 이 프로젝트는 상태 전이, retry, recovery, query normalization 같은 서비스 로직의 리스크가 더 크므로 그 지점을 우선 커버하는 것이 효율적이다.
- 외부 Worker 연동은 사용자 관찰 가능 실패 semantics와 재시도 정책이 몰려 있으므로 branch coverage 효율이 특히 높다.

## 영향

- 장점:
  - 커버리지 개선을 숫자로 확인할 수 있다.
  - 리스크가 큰 서비스/스케줄러/설정 경계를 먼저 고정할 수 있다.
- 단점:
  - private nested type, generated boilerplate는 상대적으로 미커버 상태로 남을 수 있다.
- 향후 개선:
  - coverage threshold가 필요해지면 `jacocoTestCoverageVerification`을 추가한다.
  - CI에서 HTML artifact 또는 XML summary를 게시한다.
