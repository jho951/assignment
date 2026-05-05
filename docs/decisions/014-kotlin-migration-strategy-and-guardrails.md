# ADR 014: Kotlin 점진 마이그레이션 전략과 요구사항 보호 가드레일

## 상태

채택

## 배경

현재 구현은 Java 17, Spring Boot, JPA, Lombok, Java record, Mockito 기반으로 구성되어 있다.
요구사항의 핵심 가치는 언어 선택 자체가 아니라 공개 API contract, 상태 전이, idempotency, Worker retry/recovery, 재시작 복구, 결과 보존 semantics 를 안정적으로 유지하는 데 있다.
따라서 Kotlin 전환은 생산성 향상보다도 기존 동작을 깨지 않는 순서와 경계 관리가 더 중요하다.

## 결정

- Kotlin 마이그레이션은 Java/Kotlin 혼합 상태를 허용하는 점진적 전환으로 수행한다.
- build 에 Kotlin 지원을 추가할 때는 Spring proxy 와 JPA 동작을 위해 Kotlin JVM, Spring, JPA plugin 과 `kotlin-reflect`, `jackson-module-kotlin` 호환성을 함께 확보한다.
- 공개 API contract, JSON field 이름, status 이름, error code, pagination/filter semantics, DB schema naming, Worker integration semantics 는 리팩터링 중에도 유지한다.
- 마이그레이션 순서는 low-risk type 부터 시작하고 high-risk type 을 뒤로 미룬다.
- 우선순위는 DTO/record, `@ConfigurationProperties`, 순수 service, controller/configuration, Worker/processing, JPA entity 순서로 둔다.
- Spring bean 으로 관리되는 Kotlin class 는 proxy compatibility 를 보장하는 build plugin 을 사용하고, 수동 `open` 남발로 해결하지 않는다.
- JPA entity 는 Kotlin `data class`로 옮기지 않고 일반 class 로 유지하며, no-arg/open 요구사항과 mutable field semantics 를 명시적으로 유지한다.
- nullable request/response/persistence field 는 Kotlin type 에서 `?`로 명확히 표현하고, 지금의 validation 및 exception 흐름을 프레임워크 기본 에러로 바꾸지 않는다.
- 테스트는 interface mocking 과 constructor injection 을 우선 유지하고, concrete Kotlin class mocking 이 꼭 필요하면 final class 대응 전략을 함께 도입한다.
- Lombok 제거는 대응하는 Java class 가 Kotlin 으로 완전히 이동한 뒤에 수행한다.
- 각 마이그레이션 배치마다 `./gradlew test jacocoTestReport`를 실행하고, REQUIREMENTS/ADR/prompt/runbook 을 같이 갱신한다.

## 이유

- mixed-language 전환은 큰 충돌을 작은 배치로 쪼개 요구사항 회귀를 줄인다.
- Kotlin plugin 조합 없이 Spring/JPA class 를 변환하면 final/no-arg 문제로 런타임 장애가 발생하기 쉽다.
- DTO, properties, 순수 service 는 JPA entity 나 scheduler 보다 부작용 반경이 작아 초기 전환 대상으로 적합하다.
- entity 를 `data class`로 바꾸면 equality, proxy, lazy loading, copy semantics 가 현재 도메인 모델과 충돌할 수 있다.
- 기존 테스트가 concrete class mocking 을 사용하므로 테스트 전략을 무시한 언어 전환은 바로 회귀로 이어질 가능성이 높다.

## 영향

- 장점:
  - 요구사항 회귀 없이 Kotlin 전환 범위를 단계적으로 늘릴 수 있다.
  - API/DB/Worker semantics 를 언어 변경과 분리해 검증할 수 있다.
  - JPA/Spring proxy 와 테스트 인프라에서 자주 발생하는 전환 오류를 초기에 차단할 수 있다.
- 단점:
  - 한동안 Java 와 Kotlin 이 공존하므로 코드 스타일이 혼재한다.
  - Lombok 제거와 테스트 전략 정리가 별도 작업으로 남는다.
- 검증:
  - `./gradlew test jacocoTestReport`
  - controller/service/processor/worker integration semantics 유지 확인
  - Kotlin 관련 compile/runtime failure 시 `docs/runbook/DEBUG.md`의 Kotlin migration 점검 절차 사용
