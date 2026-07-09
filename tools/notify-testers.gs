/**
 * EverInk 베타 테스터 자동 알림 (Google Apps Script)
 *
 * 새 GitHub 릴리스(프리릴리스 포함)가 올라오면, 테스터에게 다운로드 링크를
 * 이메일로 자동 발송한다. GitHub의 /releases/latest 는 프리릴리스를 제외하므로
 * 여기서는 /releases 목록의 가장 최근 항목을 직접 조회한다.
 *
 * 설치 방법은 이 폴더의 README.md 참고.
 */

// ── 설정 ────────────────────────────────────────────────────────────
const REPO = 'whiteheron96-svg/everink';

// 테스터 이메일을 여기에 추가/삭제하세요. 각 주소로 개별 발송됩니다
// (서로의 주소가 노출되지 않습니다).
const TESTERS = [
  'tester1@example.com',
  'tester2@example.com',
];

const FROM_NAME = 'EverInk';   // 수신자에게 보이는 발신자 이름
const CHECK_EVERY_HOURS = 1;   // 몇 시간마다 새 릴리스를 확인할지
// ────────────────────────────────────────────────────────────────────

/**
 * 시간 트리거가 주기적으로 호출한다. 최신 릴리스(프리릴리스 포함)를 확인하고,
 * 아직 알린 적 없는 새 버전이면 테스터에게 메일을 보낸다.
 */
function checkAndNotify() {
  const release = fetchLatestRelease_();
  if (!release) return;

  const props = PropertiesService.getScriptProperties();
  if (release.tag_name === props.getProperty('lastNotifiedTag')) {
    return;   // 이미 알린 버전 — 중복 발송 방지
  }

  const sent = sendReleaseMail_(release);
  if (sent > 0) {
    props.setProperty('lastNotifiedTag', release.tag_name);
    console.log(`${release.tag_name} 안내를 테스터 ${sent}명에게 발송했습니다.`);
  }
}

/** /releases 목록의 첫 항목(가장 최근, 프리릴리스 포함)을 돌려준다. 실패 시 null. */
function fetchLatestRelease_() {
  const url = `https://api.github.com/repos/${REPO}/releases?per_page=1`;
  const res = UrlFetchApp.fetch(url, {
    headers: { 'Accept': 'application/vnd.github+json' },
    muteHttpExceptions: true,
  });
  if (res.getResponseCode() !== 200) {
    console.warn(`GitHub API 오류 ${res.getResponseCode()}: ${res.getContentText().slice(0, 200)}`);
    return null;
  }
  const list = JSON.parse(res.getContentText());
  return list.length ? list[0] : null;
}

/** 릴리스 안내 메일을 테스터에게 개별 발송하고, 성공 건수를 돌려준다. */
function sendReleaseMail_(release) {
  const tag = release.tag_name;
  const pageUrl = release.html_url;                 // .../releases/tag/vX.Y.Z
  const arm64 = pickAsset_(release, /arm64-v8a.*\.apk$/);

  const subject = `EverInk ${tag} 업데이트가 나왔어요`;
  const body =
    `EverInk 새 버전 ${tag}이(가) 올라왔습니다.\n\n` +
    `▶ 다운로드(전체 안내): ${pageUrl}\n` +
    (arm64 ? `▶ 대부분의 폰(arm64): ${arm64}\n` : '') +
    `\n기존 버전 위에 그대로 설치하면 됩니다 — 문서와 주석은 유지됩니다.\n\n` +
    `── 변경 내용 ──\n${(release.body || '(설명 없음)').trim()}\n`;

  let sent = 0;
  TESTERS.forEach(function (addr) {
    try {
      GmailApp.sendEmail(addr, subject, body, { name: FROM_NAME });
      sent++;
    } catch (e) {
      console.warn(`${addr} 발송 실패: ${e.message}`);
    }
  });
  return sent;
}

/** 릴리스 자산 중 정규식과 일치하는 첫 APK의 다운로드 URL(없으면 null). */
function pickAsset_(release, re) {
  const a = (release.assets || []).find(function (x) { return re.test(x.name); });
  return a ? a.browser_download_url : null;
}

// ── 아래 두 함수는 최초 설정 시 한 번씩 수동 실행 ──────────────────────

/**
 * 자동 확인 트리거를 설치한다(최초 1회만 실행). 기존 트리거는 먼저 제거해
 * 중복 설치를 막는다. 실행하면 권한 승인 창이 뜬다.
 */
function installTrigger() {
  ScriptApp.getProjectTriggers()
    .filter(function (t) { return t.getHandlerFunction() === 'checkAndNotify'; })
    .forEach(function (t) { ScriptApp.deleteTrigger(t); });
  ScriptApp.newTrigger('checkAndNotify').timeBased().everyHours(CHECK_EVERY_HOURS).create();
  console.log(`${CHECK_EVERY_HOURS}시간마다 자동 확인하도록 설정했습니다.`);
}

/**
 * 현재 최신 릴리스를 "이미 알림" 상태로 표시해 다음 자동 실행이 이 버전을
 * 발송하지 않게 한다. 이미 수동으로 안내한 버전을 건너뛰고 싶을 때 실행.
 */
function markCurrentAsNotified() {
  const release = fetchLatestRelease_();
  if (!release) return;
  PropertiesService.getScriptProperties().setProperty('lastNotifiedTag', release.tag_name);
  console.log(`${release.tag_name}을(를) 이미 알림으로 표시했습니다.`);
}
