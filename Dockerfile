# 빌드: 저장소 전체가 컨텍스트여야 core를 함께 컴파일할 수 있다
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :playground:installDist --no-daemon -q

# 실행: JRE만
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/playground/build/install/playground /app
ENV PORT=8080 GWANBASE_DB=/app/data/playground.db
RUN mkdir -p /app/data
EXPOSE 8080
CMD ["/app/bin/playground"]
