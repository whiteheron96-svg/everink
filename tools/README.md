# tools/

저장소 코드가 아니라, 운영을 돕는 보조 스크립트가 있는 곳.

## notify-testers.gs — 베타 테스터 자동 알림

새 GitHub 릴리스(프리릴리스 포함)가 올라오면 테스터에게 다운로드 링크를
이메일로 자동 발송하는 [Google Apps Script](https://script.google.com)다.
GitHub의 `/releases/latest`는 프리릴리스를 제외하므로, 이 스크립트는
`/releases` 목록의 가장 최근 항목을 직접 조회한다.

### 설치 (최초 1회)

1. https://script.google.com 에서 **새 프로젝트** 생성.
2. 기본 `Code.gs` 내용을 `notify-testers.gs` 내용으로 전부 교체.
3. 상단 `TESTERS` 배열에 테스터 이메일을 채운다.
   (필요하면 `CHECK_EVERY_HOURS`로 확인 주기 조정 — 기본 1시간.)
4. 함수 선택 메뉴에서 **`installTrigger`** 를 골라 ▶ 실행.
   - 최초 실행 시 Gmail·외부요청 권한 승인 창이 뜬다 → 승인.
   - 이후 설정한 주기마다 자동으로 새 릴리스를 확인한다.

### 동작

- 새 태그가 감지되면 그 버전을 한 번만 발송하고, 마지막으로 알린 태그를
  `ScriptProperties`에 저장해 **중복 발송을 막는다**.
- 각 테스터에게 **개별 발송**하므로 서로의 주소가 노출되지 않는다.
- 메일에는 릴리스 페이지 링크 + arm64 APK 직접 링크 + 릴리스 노트가 담긴다.

### 알아둘 점

- **최초 자동 실행은 현재 최신 버전을 발송한다.** 이미 수동으로 안내한
  버전을 건너뛰려면, `installTrigger` 전에 **`markCurrentAsNotified`** 를
  한 번 실행해 현재 버전을 "이미 알림"으로 표시한다.
- 지금 바로 한 통 보내 확인하고 싶으면 **`checkAndNotify`** 를 수동 실행.
- 소비자용 Gmail은 하루 발송 한도가 약 100명이다(소규모 베타엔 충분).
