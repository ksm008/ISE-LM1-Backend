# ISE-LM1 Backend

웹 브라우저에서 격리된 Docker 컨테이너 셸에 접속하고, 해당 컨테이너의 eBPF 로그를 실시간으로 받아볼 수 있는 보안 실습 환경 백엔드 서버입니다.

## 주요 기능

- **웹 터미널 (WebSocket)**: 접속할 때마다 원격 Docker 호스트에 실습용 컨테이너(`sec-lab-ubuntu:1.0`)를 새로 만들고 `/bin/bash` 셸을 연결합니다. 연결이 끊기면 컨테이너는 자동으로 삭제됩니다.
- **eBPF 로그 스트리밍 (SSE)**: 실습 서버의 eBPF 에이전트가 수집한 컨테이너 로그를 Server-Sent Events로 클라이언트에 전달합니다.
- **컨테이너 원격 실행 테스트**: Docker 호스트 연결을 확인하기 위한 테스트용 엔드포인트입니다.

## 기술 스택

| 구분 | 사용 기술 |
| --- | --- |
| Language | Java 17 |
| Framework | Spring Boot 4.1.2 (Web MVC, WebSocket) |
| Docker 연동 | docker-java 3.3.6 (Apache HttpClient5 transport) |
| Build | Gradle |
| 기타 | Lombok |

## 프로젝트 구조

```
src/main/java/iselm1/
├── IseLm1Application.java          # 애플리케이션 진입점
├── docker/
│   ├── DockerConfig.java           # 원격 Docker 호스트 DockerClient 설정
│   └── DockerController.java       # 컨테이너 실행 테스트 API
├── ebpf/
│   ├── EbpfLogService.java         # eBPF 에이전트 스트림 → SSE 중계
│   └── LogController.java          # 로그 구독 API
└── websocket/
    ├── WebSocketConfig.java        # WebSocket 엔드포인트 등록
    └── TerminalWebSocketHandler.java  # 컨테이너 생성/셸 연결/정리
```

> `IseLm1Application`은 루트 패키지(`iselm1`)에 있어야 합니다. Spring은 이 클래스가 있는 패키지와 그 하위 패키지만 컴포넌트 스캔합니다.

## API

| 방식 | 엔드포인트 | 설명 |
| --- | --- | --- |
| WebSocket | `/ws/terminal?labId={labId}` | 실습 컨테이너 생성 후 bash 셸 연결. 메시지로 키 입력을 보내면 터미널 출력이 돌아옵니다. `labId`를 주면 eBPF 로그 스트리밍도 시작합니다. |
| GET (SSE) | `/api/logs?labId={labId}` | 해당 `labId`의 eBPF 로그를 `text/event-stream`으로 구독합니다. |
| GET | `/start-container` | `arm64v8/alpine` 컨테이너를 원격으로 실행해 Docker 연결을 확인합니다. |

### 사용 흐름

1. 클라이언트가 `/api/logs?labId=lab-1`로 SSE 구독을 엽니다.
2. 같은 `labId`로 `/ws/terminal?labId=lab-1`에 WebSocket을 연결합니다.
3. 서버가 컨테이너를 만들고 셸을 연결하며, eBPF 에이전트에 해당 컨테이너의 로그 스트림을 요청합니다.
4. 터미널 출력은 WebSocket으로, eBPF 로그는 SSE로 전달됩니다.
5. WebSocket 연결이 끊기면 컨테이너 삭제와 로그 스트림 종료가 함께 처리됩니다.

## 실행 환경

이 서버는 원격 실습 서버(Tailscale 네트워크)에 다음 두 가지가 떠 있어야 동작합니다.

| 대상 | 주소 | 설정 위치 |
| --- | --- | --- |
| Docker Engine API | `tcp://100.100.215.116:2375` | `docker/DockerConfig.java` |
| eBPF 로그 에이전트 | `http://100.100.215.116:9000/stream` | `ebpf/EbpfLogService.java` |

실습 서버에는 `sec-lab-ubuntu:1.0` 이미지가 있어야 합니다. 없으면 첫 접속 때 pull을 시도합니다.

## 실행 방법

```bash
# 빌드
./gradlew build

# 실행 (기본 포트 8080)
./gradlew bootRun
```

Windows에서는 `gradlew.bat`을 사용합니다.
