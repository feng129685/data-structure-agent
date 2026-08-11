ARG JAVA_BUILD_IMAGE=eclipse-temurin:21-jdk
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:21-jre
FROM ${JAVA_BUILD_IMAGE} AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw mvnw
COPY mvnw.cmd mvnw.cmd
COPY pom.xml pom.xml
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -q -DskipTests package

FROM ${JAVA_RUNTIME_IMAGE}

RUN useradd --system --uid 10001 --create-home appuser
WORKDIR /app
COPY --from=build /workspace/target/ds-agent-server-*.jar app.jar

USER appuser
EXPOSE 8792

ENTRYPOINT ["java", "-Xms64m", "-Xmx192m", "-Xss512k", "-XX:MaxMetaspaceSize=96m", "-XX:MaxDirectMemorySize=32m", "-XX:+UseSerialGC", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]
