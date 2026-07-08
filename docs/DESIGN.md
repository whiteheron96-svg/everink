# EverInk 디자인 시스템 — "잉크 & 종이"

만년필 잉크와 종이의 감성. "주석은 영원히 남는다"는 제품 약속을 시각화한다.
모든 값은 `res/values/colors.xml`(라이트) · `res/values-night/colors.xml`(다크)에
토큰으로 정의하며, 프로그래매틱 UI는 `app.everink.ui.InkUi` 헬퍼를 쓴다.

## 색 (Color Tokens)

| 토큰 | 라이트 | 다크(심야 잉크) | 용도 |
|---|---|---|---|
| `paper_bg` | `#FAF6EE` | `#131722` | 화면 배경(종이) |
| `paper_surface` | `#FFFFFF` | `#1C2230` | 카드·다이얼로그 표면 |
| `outline_soft` | `#E3DAC6` | `#353C4A` | 옅은 경계선 |
| `ink_strong` | `#1C1B18` | `#EAE6DC` | 본문 텍스트(먹색) |
| `ink_dim` | `#6B6455` | `#A69F90` | 보조 텍스트 |
| `ink_primary` | `#1B3A6B` | `#9FB8E6` | 브랜드 잉크 네이비 |
| `amber` | `#F5B301` | `#F5B301` | 주석 앰버(포인트) |
| `btn_bg` / `btn_fg` | 네이비/화이트 | `#2F4C7C`/`#EAE6DC` | 주 버튼 |
| `overlay_bg` / `overlay_fg` | 짙은 잉크 90% / 크림 | 동일 계열 | 뷰어 오버레이 필 |
| `viewer_bg` | `#24272E` | `#0D1117` | 문서 주변 배경 |

## 모양 (Shape)

- 주 버튼: 필(pill) 26dp 라운드, 높이 52dp
- 오버레이(도구바·상태): 필 18–22dp 라운드
- 카드: 12dp 라운드 + 1dp `outline_soft` 외곽선

## 타이포그래피

시스템 산세리프 사용.
- 워드마크: 34sp Bold — "Ever"(`ink_primary`) + "Ink"(`amber`)
- 카드 제목 15sp Bold · 본문 13.5sp · 보조 12sp · 캡션 11.5sp (`ink_dim`)

## 컴포넌트 규칙

- **다이얼로그**: `Theme.EverInk`(Material3 오버라이드)를 그대로 따른다. 개별 색 지정 금지.
- **뷰어 오버레이**: 항상 `overlay_bg` 필 + `overlay_fg` 텍스트. 시스템 바 인셋 + 10dp 마진.
- **주석 시각화**: 메모 하이라이트=앰버 계열, 필기 기본 펜=잉크 네이비 (`펜` 4색: 파랑·빨강·초록·검정).
- **런처 아이콘**: 종이 크림 배경 + 네이비 만년필 닙 + 앰버 잉크 방울 (`ic_launcher_fg.xml`, adaptive+monochrome).

## 원칙

1. 색은 반드시 토큰으로 — 코드에 hex 직접 쓰지 않는다 (잉크 미리보기 펜 색 제외).
2. 라이트/다크는 `values-night` 오버라이드만으로 성립해야 한다.
3. 포인트 색(앰버)은 화면당 한 요소에만.
