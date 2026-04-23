FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="Contract Guardian" \
      org.opencontainers.image.description="Unified schema governance for microservices" \
      org.opencontainers.image.source="https://github.com/vyndra-io/contract-guardian"

RUN addgroup -S guardian && adduser -S guardian -G guardian

WORKDIR /app

COPY contract-guardian-cli/target/contract-guardian-cli-1.0.0.jar /app/contract-guardian.jar

USER guardian

ENTRYPOINT ["java", "-jar", "/app/contract-guardian.jar"]
