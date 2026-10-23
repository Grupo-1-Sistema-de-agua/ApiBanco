# ---------- Etapa 1: compilar ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Incluye src/main/resources/openapi/bank-payments-api.yaml
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Etapa 2: imagen final (solo el JRE) ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
