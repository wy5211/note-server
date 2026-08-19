# ---------- 阶段 1：构建 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml ./
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd -r appuser
USER appuser

COPY --from=build /app/target/note-server-*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai"

EXPOSE 9092
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
