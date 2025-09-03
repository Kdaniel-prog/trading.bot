# Build stage
FROM maven:3.9.2-eclipse-temurin-17 AS build
WORKDIR /app

# Másoljuk a pom.xml-t és a kódot
COPY pom.xml .
COPY src ./src

# Buildeljük a Spring Boot jar-t
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre
WORKDIR /app

# Másoljuk át a JAR-t a build stage-ből
COPY --from=build /app/target/trading.bot-0.0.1-SNAPSHOT.jar app.jar

# Port, amit Fly.io-n ki fogunk nyitni
EXPOSE 8080

# Indítás
ENTRYPOINT ["java","-jar","app.jar"]
