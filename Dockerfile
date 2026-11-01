
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B package dependency:copy-dependencies -DoutputDirectory=target/dependency

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/classes ./target/classes
COPY --from=build /app/target/dependency ./target/dependency
EXPOSE 10000
CMD ["sh", "-c", "java -cp 'target/classes:target/dependency/*' ChatServer & java -cp 'target/classes:target/dependency/*' WebGateway & java -cp 'target/classes:target/dependency/*' WebFiles"]
