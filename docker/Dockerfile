FROM gradle:8.14.0-jdk17 AS build

WORKDIR /workspace

# 현재 경로(.) 기준으로 복사하도록 수정
COPY gradle gradle
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY src src

RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
