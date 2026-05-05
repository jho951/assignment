# ADR 011: Lombok으로 반복 보일러플레이트 축소

## 상태

대체됨

## 배경

Kotlin 마이그레이션 이전의 Java 중심 코드베이스에는 생성자 주입, 단순 getter/setter, 예외 필드 접근자처럼 반복적인 보일러플레이트가 많았다.
과제의 핵심은 비동기 처리, 상태 전이, Worker 연동, 복구 정책이지 수동 접근자 구현 자체가 아니었다.
다만 JPA 엔티티와 트랜잭션 경계가 있는 코드에서는 Lombok을 무분별하게 쓰면 의도를 흐릴 수 있으므로 적용 범위를 제한할 필요가 있었다.

## 결정

- Java 중심 구현 단계에서만 Lombok 의존성과 annotation processor를 유지한다.
- 생성자 주입만 필요한 Spring 빈은 `@RequiredArgsConstructor`를 사용한다.
- 단순 필드 접근자만 필요한 예외 타입과 JPA 엔티티는 `@Getter`를 사용한다.
- JPA 엔티티의 setter는 실제로 변경이 필요한 mutable 필드에만 선택적으로 노출한다.
- JPA 엔티티의 no-args constructor는 `@NoArgsConstructor(access = PROTECTED)`로 유지한다.
- 복잡한 생성 로직이나 런타임 초기화가 필요한 클래스는 수동 constructor를 유지한다.
- 모든 main Java source 가 Kotlin 으로 전환되면 Lombok plugin/dependency 는 build 에서 제거한다.

## 이유

- Java 단계에서 핵심 비즈니스 로직 대비 반복 코드 비중을 줄일 수 있었다.
- 생성자 누락이나 접근자 실수 같은 단순 오류 가능성을 낮출 수 있었다.
- JPA entity에 전체 setter를 열지 않고 선택적으로 적용하면 도메인 경계를 비교적 보존할 수 있었다.
- 계산이 필요한 constructor까지 Lombok으로 밀어붙이지 않으면 가독성과 디버깅 편의성을 유지할 수 있었다.
- Kotlin 전환이 완료된 이후에는 같은 목적을 Kotlin 언어 기능과 명시적 도메인 메서드로 대체하는 편이 build surface 를 더 단순하게 만든다.

## 영향

- 장점:
  - Java 단계에서 서비스, 컨트롤러, 스케줄러의 코드가 간결해졌다.
  - Java 엔티티와 예외 클래스의 반복 접근자 코드가 줄었다.
- 단점:
  - IDE annotation processing 설정이 꺼져 있으면 개발자가 컴파일 문제를 겪을 수 있었다.
  - Lombok에 익숙하지 않은 사람에게는 생성 코드가 보이지 않아 진입 장벽이 생길 수 있었다.
- 향후 개선:
  - queue claim query나 복잡한 엔티티 변경 메서드가 도입되면 setter 대신 명시적 도메인 메서드로 전환을 검토한다.
- 후속 상태:
  - 2026-05-06 기준 main source 는 Kotlin 으로 모두 이전됐고 `build.gradle`에서 Lombok 관련 plugin/dependency 를 제거했다.
