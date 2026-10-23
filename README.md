# 차곡 Server

Spring Boot 애플리케이션은 `chagok/`에 있습니다. Java 25와 Gradle Wrapper를 사용합니다.

```sh
cd chagok
./gradlew offlineTest bootJar
```

GitHub Actions는 PR에서 테스트·컨테이너 기동을 검증하고, main 반영 시 GHCR 이미지를 발행합니다.
서버 설정과 배포·롤백 절차는 [CI/CD 안내](chagok/docs/cicd.md)를 참고하세요.
