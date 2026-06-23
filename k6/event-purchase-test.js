import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 선착순 이벤트 구매 동시성 테스트 (DB 비관적 락 검증)
// docker run --rm -i grafana/k6 run - < k6/event-purchase-test.js
//
// 흐름: 대기열 join → Scheduler(3초마다)가 구매 권한 부여 → 순번 폴링으로 ready 확인 → purchase
//       구매는 eventRepository.findWithLock(비관적 락) 으로 재고 차감 → 정확히 capacity 만큼만 성공.
//
// 목적: capacity=N 이벤트에 M명이 동시에 달려들어도 정확히 N건만 구매 성공, 오버셀(remaining<0) 0.
//       (재고 분산락 테스트와 달리 이쪽은 '비관적 락' 경로라 별도 검증 가치가 있음)

const purchaseSuccess = new Counter('purchase_success');  // 200 — 구매 성공
const soldOut         = new Counter('sold_out');          // 400 — 재고 소진/이미 구매/권한 만료
const notReady        = new Counter('not_ready');         // 폴링 시간 내 구매 권한 못 받음
const errorResponse   = new Counter('error_response');    // 그 외 (5xx 등)

const VU_COUNT = 20;   // 동시 참여 인원
const CAPACITY = 10;   // 이벤트 한정 수량 (이 수만큼만 구매돼야 정상)
const STORE_ID = 1;
const GOODS_ID = 1;    // 고래 텀블러 (data.sql 시드)

const POLL_MAX     = 18;   // 순번 폴링 최대 횟수
const POLL_WAIT_S  = 2;    // 폴링 간격(초) — Scheduler 가 3초마다 도므로 2초로 충분히 커버

export const options = {
  scenarios: {
    rush: {
      executor: 'per-vu-iterations',
      vus: VU_COUNT,
      iterations: 1,
      maxDuration: '90s',
    },
  },
};

const BASE_URL = 'http://host.docker.internal:8080';
const JSON_HDR = { 'Content-Type': 'application/json' };

// ── setup: 용량 작은 새 이벤트 생성 + 강제 오픈 + 참가자 계정 준비 ──
export function setup() {
  const adminRes = http.post(`${BASE_URL}/api/auth/login`,
    JSON.stringify({ userId: 'admin', password: 'adminadmin' }), { headers: JSON_HDR });
  const adminToken = adminRes.json('data.accessToken');
  if (!adminToken) {
    console.log(`❌ 어드민 로그인 실패 status=${adminRes.status} body=${adminRes.body}`);
    return { eventId: null };
  }
  const adminHdr = { ...JSON_HDR, Authorization: `Bearer ${adminToken}` };

  // 매 실행마다 새 이벤트 → remaining_capacity 가 항상 CAPACITY 에서 시작 (결정성)
  const openAt = new Date(Date.now() - 60_000).toISOString().slice(0, 19); // 과거 시각, ISO LocalDateTime
  const createRes = http.post(`${BASE_URL}/api/admin/events`,
    JSON.stringify({
      name: `k6 선착순 테스트 ${Date.now()}`,
      goodsId: GOODS_ID,
      storeId: STORE_ID,
      openAt,
      capacity: CAPACITY,
      perPersonLimit: 1,
    }),
    { headers: adminHdr });
  const eventId = createRes.json('data.eventId');
  if (!eventId) {
    console.log(`❌ 이벤트 생성 실패 status=${createRes.status} body=${createRes.body}`);
    return { eventId: null };
  }

  // 강제 오픈 (정상 흐름은 Scheduler 가 openAt 도래 시 자동 오픈)
  const openRes = http.patch(`${BASE_URL}/api/admin/events/${eventId}/open`, null, { headers: adminHdr });
  check(openRes, { '이벤트 오픈 성공': (r) => r.status === 200 });

  // 참가자 계정 생성 + 로그인
  const tokens = [];
  for (let i = 1; i <= VU_COUNT; i++) {
    const userId = `evttest_${eventId}_${i}`;
    const pw     = 'evttest1';
    http.post(`${BASE_URL}/api/admin/members`,
      JSON.stringify({ userId, password: pw, name: userId, nickname: userId, role: 'CUSTOMER' }),
      { headers: adminHdr });
    const loginRes = http.post(`${BASE_URL}/api/auth/login`,
      JSON.stringify({ userId, password: pw }), { headers: JSON_HDR });
    tokens.push(loginRes.json('data.accessToken') || '');
  }

  console.log(`\n준비 완료: eventId=${eventId}, capacity=${CAPACITY}, 참가자 ${tokens.filter(Boolean).length}/${VU_COUNT}명`);
  console.log(`→ ${VU_COUNT}명이 선착순 구매 시작 (기대: ${CAPACITY}건만 성공)\n`);
  return { eventId, tokens, capacity: CAPACITY };
}

// ── 메인: 대기열 진입 → 순번 폴링 → 구매 ──────────────────────────
export default function ({ eventId, tokens }) {
  if (!eventId) { errorResponse.add(1); return; }
  const token = tokens[__VU - 1];
  if (!token) { errorResponse.add(1); return; }

  const headers = { ...JSON_HDR, Authorization: `Bearer ${token}` };

  // 1. 대기열 진입 — RateLimiter 초과(429) 시 잠깐 쉬고 재시도
  let joined = false;
  for (let attempt = 1; attempt <= 5; attempt++) {
    const joinRes = http.post(`${BASE_URL}/api/events/${eventId}/queue/join`, null, { headers });
    if (joinRes.status === 200) { joined = true; break; }
    if (joinRes.status !== 429) {
      console.log(`VU${__VU} join 실패: status=${joinRes.status} body=${joinRes.body}`);
      break;
    }
    sleep(0.5);
  }
  if (!joined) { errorResponse.add(1); return; }

  // 2. 구매 권한(ready) 폴링 — Scheduler 가 순번대로 부여
  let ready = false;
  for (let i = 0; i < POLL_MAX; i++) {
    sleep(POLL_WAIT_S);
    const st = http.get(`${BASE_URL}/api/events/${eventId}/queue/status`, { headers });
    if (st.status !== 200) continue;
    if (st.json('data.isReady') === true)   { ready = true; break; }
    if (st.json('data.purchased') === true)  { ready = true; break; }
  }
  if (!ready) { notReady.add(1); return; }

  // 3. 구매 — 비관적 락이 capacity 초과를 막음
  const buyRes = http.post(`${BASE_URL}/api/events/${eventId}/purchase`, null, { headers });
  if (buyRes.status === 200) {
    purchaseSuccess.add(1);
  } else if (buyRes.status === 400) {
    soldOut.add(1);   // 재고 소진 / 이미 구매 / 권한 만료
  } else {
    errorResponse.add(1);
    console.log(`VU${__VU} 구매 실패: status=${buyRes.status} body=${buyRes.body}`);
  }
}

// ── teardown: 최종 remaining_capacity 로 오버셀 검증 + 이벤트 정리 ──
export function teardown(data) {
  if (!data.eventId) {
    console.log(`\n❌ 검증 불가: setup 에서 이벤트 생성 실패\n`);
    return;
  }
  sleep(2);

  const adminRes = http.post(`${BASE_URL}/api/auth/login`,
    JSON.stringify({ userId: 'admin', password: 'adminadmin' }), { headers: JSON_HDR });
  const adminHdr = { ...JSON_HDR, Authorization: `Bearer ${adminRes.json('data.accessToken')}` };

  const evRes = http.get(`${BASE_URL}/api/events/${data.eventId}`, { headers: adminHdr });
  const remaining = evRes.status === 200 ? evRes.json('data.remainingCapacity') : null;
  const capacity  = data.capacity;
  const sold      = remaining === null ? null : capacity - remaining;

  console.log(`\n========================================`);
  console.log(`이벤트 capacity:        ${capacity}개`);
  if (remaining === null) {
    console.log(`❌ 이벤트 조회 실패 — 검증 불가`);
  } else {
    console.log(`최종 remaining:         ${remaining}개`);
    console.log(`판매 수량(capacity-남음): ${sold}개`);
    console.log(`----------------------------------------`);
    if (remaining < 0) {
      console.log(`💥 오버셀 발생: remaining 음수 → 비관적 락 오류`);
    } else if (sold > capacity) {
      console.log(`💥 초과 판매: ${sold} > ${capacity} → 비관적 락 오류`);
    } else {
      console.log(`✅ 판매 ${sold}건 ≤ capacity ${capacity}건, 음수 없음 → 비관적 락 정상`);
      console.log(`   (${VU_COUNT}명 참여 중 ${sold}건만 구매 성공, 나머지는 재고 소진/권한 미부여)`);
    }
  }
  console.log(`→ purchase_success / sold_out / not_ready / error 메트릭으로 분포 확인`);
  console.log(`========================================\n`);

  // 테스트 이벤트 종료 (정리)
  http.patch(`${BASE_URL}/api/admin/events/${data.eventId}/close`, null, { headers: adminHdr });
}

// ── 결과 요약 ─────────────────────────────────────────────────────
export function handleSummary(data) {
  const ok   = data.metrics.purchase_success?.values.count ?? 0;
  const sold = data.metrics.sold_out?.values.count         ?? 0;
  const nr   = data.metrics.not_ready?.values.count        ?? 0;
  const err  = data.metrics.error_response?.values.count   ?? 0;
  console.log(`\n========================================`);
  console.log(`🎁 구매 성공(200):       ${ok}건  (기대: capacity=${CAPACITY})`);
  console.log(`🚫 구매 거절(400):       ${sold}건  → 재고 소진/이미 구매`);
  console.log(`⏳ 권한 미부여(폴링만료): ${nr}건  → 순번 밀려 capacity 밖`);
  console.log(`❌ 에러:                 ${err}건`);
  console.log(`→ 오버셀 여부는 위 teardown 의 remaining 검증 참고`);
  console.log(`========================================\n`);
  return {};
}
