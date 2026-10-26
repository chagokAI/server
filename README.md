# 차곡 백엔드

Core APIs for warehouse operations and AI-powered packaging workflows.

차곡 백엔드는 물류센터의 업무 데이터와 처리 흐름을 관리하는 Spring Boot 애플리케이션입니다.
상품·재고·발주를 연결하고, 이미지 분석과 AI 제안 결과를 실제 업무에 반영합니다. 화면과 에이전트가 사용하는 API를 함께 제공합니다.

[로컬 실행](chagok/docs/local-development.md) · [API 명세](chagok/docs/api-specs.md) · [배포 안내](chagok/docs/cicd.md)

## ✨ 주요 기능

- **역할과 센터에 맞는 접근** — 세션 로그인과 사용자 역할·센터별 접근 권한을 관리합니다.
- **입고부터 발주까지** — 상품, 박스 규격, 재고와 발주 데이터를 관리하고 업무 변경을 반영합니다.
- **분석 결과를 실제 포장으로** — 촬영 이미지를 접수하고 Kafka 분석 결과를 박스 배정과 재고에 반영합니다.
- **업무와 연결되는 AI 제안** — 주문 제안과 본부 리포트의 실행·승인·반려를 중계하고 결과를 저장합니다.
- **화면으로 이어지는 실시간 상태** — 센터 현황과 작업 진행을 SSE로 전달하고 알림 이력을 관리합니다.

## 🗂️ 프로젝트 구성

```text
chagok/
├── src/main/java/com/skala/chagok/
│   ├── domain/       # 상품·박스·재고·발주·AI 작업
│   ├── global/       # 인증·설정·공통 응답·예외 처리
│   ├── dlanalysis/   # 이미지 접수와 Kafka 분석 연동
│   └── live/         # 실시간 센터 스트림과 데모 입력
├── src/main/resources/  # 애플리케이션 설정과 리소스
├── src/test/            # API·업무 로직·연동 테스트
└── docs/                # API 계약과 개발·운영 안내
```
