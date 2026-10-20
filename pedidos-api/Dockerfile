# API de pedidos (Spring Boot 4, Java 21). Imagem em duas etapas: compila com Maven, roda só com o JRE.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# As dependências vêm numa camada própria: só se baixam de novo quando o pom.xml muda.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
# curl só para a verificação de saúde. A API roda como usuário comum, nunca como root.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home loja
WORKDIR /app
COPY --from=build /app/target/pedidos-api-*.jar app.jar
USER loja
EXPOSE 8081
# Usa até 65% da memória do contêiner e morre limpo se ela acabar (o Docker reinicia).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fs http://localhost:8081/saude > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
