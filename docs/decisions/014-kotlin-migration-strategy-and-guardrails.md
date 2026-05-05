# ADR 014: Kotlin 점진 마이그레이션 전략과 요구사항 보호 가드레일

## 상태

채택

## 배경

마이그레이션 시작 시 구현은 Java 17, Spring Boot, JPA, Lombok, Java record, Mockito 기반으로 구성되어 있었다.
현재는 main source 와 test source 가 모두 Kotlin 으로 이전됐고, Spring/JPA/MockMvc/RestClient 와의 Java API interop 경계만 남아 있다.
요구사항의 핵심 가치는 언어 선택 자체가 아니라 공개 API contract, 상태 전이, idempotency, Worker retry/recovery, 재시작 복구, 결과 보존 semantics 를 안정적으로 유지하는 데 있다.
따라서 Kotlin 전환은 생산성 향상보다도 기존 동작을 깨지 않는 순서와 경계 관리가 더 중요하다.

## 결정

- Kotlin 마이그레이션은 Java/Kotlin 혼합 상태를 허용하는 점진적 전환으로 수행한다.
- build 에 Kotlin 지원을 추가할 때는 Spring proxy 와 JPA 동작을 위해 Kotlin JVM, Spring, JPA plugin 과 `kotlin-reflect`, `jackson-module-kotlin` 호환성을 함께 확보한다.
- Java source 에 Lombok 이 남아 있는 동안은 Kotlin 이 Lombok-generated getter/constructor 를 볼 수 있도록 Kotlin Lombok plugin 도 함께 유지하고, 모든 main Java source 전환 직후 제거한다.
- 공개 API contract, JSON field 이름, status 이름, error code, pagination/filter semantics, DB schema naming, Worker integration semantics 는 리팩터링 중에도 유지한다.
- 마이그레이션 순서는 low-risk type 부터 시작하고 high-risk type 을 뒤로 미룬다.
- 우선순위는 DTO/record, `@ConfigurationProperties`, 순수 service, controller/configuration, Worker/processing, JPA entity 순서로 둔다.
- low-risk record-like type 을 Kotlin 으로 옮길 때는 mixed Java/Kotlin interop 비용을 줄이기 위해 우선 `@JvmRecord`를 사용한다.
- Java 테스트나 남은 Java code 가 직접 생성하는 exception/value type 은 constructor overload 와 boolean getter naming 도 기존 shape 를 유지한다.
- Spring bean 으로 관리되는 Kotlin class 는 proxy compatibility 를 보장하는 build plugin 을 사용하고, 수동 `open` 남발로 해결하지 않는다.
- JPA entity 는 Kotlin `data class`로 옮기지 않고 일반 class 로 유지하며, no-arg/open 요구사항과 mutable field semantics 를 명시적으로 유지한다.
- nullable request/response/persistence field 는 Kotlin type 에서 `?`로 명확히 표현하고, 지금의 validation 및 exception 흐름을 프레임워크 기본 에러로 바꾸지 않는다.
- 테스트는 interface mocking 과 constructor injection 을 우선 유지하고, concrete Kotlin class mocking 이 꼭 필요하면 final class 대응 전략을 함께 도입한다.
- Lombok 제거는 대응하는 Java class 가 Kotlin 으로 완전히 이동한 뒤에 수행한다.
- 최종 상태는 main application source 와 test source 모두 Kotlin 으로 유지한다.
- 각 마이그레이션 배치마다 `./gradlew test jacocoTestReport`를 실행하고, REQUIREMENTS/ADR/prompt/runbook 을 같이 갱신한다.

## 이유

- mixed-language 전환은 큰 충돌을 작은 배치로 쪼개 요구사항 회귀를 줄인다.
- Kotlin plugin 조합 없이 Spring/JPA class 를 변환하면 final/no-arg 문제로 런타임 장애가 발생하기 쉽다.
- Kotlin 이 Java source 단계의 Lombok generated member 를 인식하지 못하면 mixed migration 중 service/query 계층이 바로 컴파일 실패한다.
- mixed 단계에서 Java interop shape 를 무시하면 `compileTestJava` 단계에서 constructor mismatch, boolean getter mismatch 같은 회귀가 즉시 발생한다.
- Kotlin 테스트에서 raw Mockito matcher 를 non-null Kotlin 시그니처에 직접 쓰면 `NullPointerException`이나 matcher misuse 가 발생할 수 있다.
- DTO, properties, 순수 service 는 JPA entity 나 scheduler 보다 부작용 반경이 작아 초기 전환 대상으로 적합하다.
- `@JvmRecord`는 Java caller 에서 기존 record accessor 와 canonical constructor 를 계속 사용할 수 있어 초기 배치 회귀 범위를 줄인다.
- entity 를 `data class`로 바꾸면 equality, proxy, lazy loading, copy semantics 가 현재 도메인 모델과 충돌할 수 있다.
- 기존 테스트가 concrete class mocking 을 사용하므로 테스트 전략을 무시한 언어 전환은 바로 회귀로 이어질 가능성이 높다.

## 영향

- 장점:
  - 요구사항 회귀 없이 Kotlin 전환 범위를 단계적으로 늘릴 수 있다.
  - API/DB/Worker semantics 를 언어 변경과 분리해 검증할 수 있다.
  - JPA/Spring proxy 와 테스트 인프라에서 자주 발생하는 전환 오류를 초기에 차단할 수 있다.
- 단점:
  - 마이그레이션 동안 Java 와 Kotlin 이 공존하므로 코드 스타일이 일시적으로 혼재한다.
  - Mockito matcher 와 Kotlin non-null 시그니처 조합은 Java 테스트보다 더 엄격한 타입 전략을 요구한다.
- 후속 상태:
  - 2026-05-06 기준 main source 와 test source 는 모두 Kotlin 으로 이전됐고 `build.gradle`에서 Kotlin Lombok plugin 과 Lombok dependency 를 제거했다.
  - `./gradlew clean test` 기준 `compileJava NO-SOURCE`, `compileTestJava NO-SOURCE`를 확인했다.
- 검증:
  - `./gradlew test jacocoTestReport`
  - controller/service/processor/worker integration semantics 유지 확인
  - Kotlin 관련 compile/runtime failure 시 `docs/runbook/DEBUG.md`의 Kotlin migration 점검 절차 사용
