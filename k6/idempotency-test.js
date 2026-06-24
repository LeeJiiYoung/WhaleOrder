import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 멱등성 / 중복 결제 방지 테스트
// docker run --rm -i grafana/k6 run - < k6/idempotency-test.js
//
// 목적: 한 유저가 동일한 결제 요청을 N개 동시 전송(더블클릭/네트워크 재시도 시뮬레이션) →
//       SHA-256 멱등성 키가 같으므로 markProcessing 에서 1개만 처리되고 나머지는 409 로 거절.
//       => 중복 주문/중복 결제가 생기지 않아야 함.
//
// 검증: (1) 응답 분포 — 처리 1건(200|402) + 중복거절 N-1건(409)
//       (2) 실제 생성된 주문 수 — GET /api/orders 로 교차 확인 (성공 주문 ≤ 1)

const paymentSuccess  = new Counter('payment_success');    // 200 — 실제 처리된 1건
const duplicateReject = new Counter('duplicate_reject');   // 409 — 멱등성 중복 거절
const paymentMockFail = new Counter('payment_mock_fail');  // 402 — 처리됐으나 Mock PG 실패(의도)
const otherResponse   = new Counter('other_response');     // 그 외 (400 빈 카트 등 / 5xx)

const VU_COUNT = 20;   // 동시에 같은 결제를 누르는 횟수
const MENU_ID  = 8;    // 옵션 없는 테스트 음료
const STORE_ID = 1;

export const options = {
  scenarios: {
    // 같은 토큰으로 20 VU 가 동시에 1회씩 → 동일 멱등성 키로 동시 진입
    duplicate_burst: {
      executor: 'per-vu-iterations',
      vus: VU_COUNT,
      iterations: 1,
      maxDuration: '30s',
    },
  },
};

const BASE_URL = 'http://host.docker.internal:8080';
const JSON_HDR = { 'Content-Type': 'application/json' };

// ── setup: 단일 유저 생성 + 재고 무제한 세팅 + 장바구니 1개 담기 ─────
export function setup() {
  const adminRes = http.post(`${BASE_URL}/api/auth/login`,
    JSON.stringify({ userId: 'admin', password: 'adminadmin' }), { headers: JSON_HDR });
  const adminToken = adminRes.json('data.accessToken');
  if (!adminToken) {
    console.log(`❌ 어드민 로그인 실패 status=${adminRes.status} body=${adminRes.body}`);
  }
  const adminHdr = { ...JSON_HDR, Authorization: `Bearer ${adminToken}` };

  // 재고를 무제한(-1)으로 — 재고가 아니라 '멱등성'만 변수로 두기 위함
  http.put(`${BASE_URL}/api/admin/stores/${STORE_ID}/stocks/${MENU_ID}`,
    JSON.stringify({ quantity: -1 }), { headers: adminHdr });

  // 매 실행마다 새 유저 → 이전 실행 주문과 섞이지 않아 주문 수 검증이 정확
  // userId 는 @Size(min=4,max=20) 제약 → 타임스탬프 뒤 10자리만 사용("idem_" + 10 = 15자)
  const userId = `idem_${Date.now().toString().slice(-10)}`;
  const pw     = 'idemtest1';
  const createRes = http.post(`${BASE_URL}/api/admin/members`,
    JSON.stringify({ userId, password: pw, name: userId, nickname: userId, role: 'CUSTOMER' }),
    { headers: adminHdr });
  if (createRes.status !== 200) {
    console.log(`❌ 테스트 유저 생성 실패 status=${createRes.status} body=${createRes.body}`);
  }

  const loginRes = http.post(`${BASE_URL}/api/auth/login`,
    JSON.stringify({ userId, password: pw }), { headers: JSON_HDR });
  const token = loginRes.json('data.accessToken');
  if (!token) {
    console.log(`❌ 테스트 유저 로그인 실패 status=${loginRes.status} body=${loginRes.body}`);
    return { token: '', expectedAmount: null };
  }
  const hdr = { ...JSON_HDR, Authorization: `Bearer ${token}` };

  // 장바구니 1개 담기 (모든 VU 가 이 동일한 카트를 읽어 같은 멱등성 키 생성)
  http.del(`${BASE_URL}/api/cart`, null, { headers: hdr });
  const addRes = http.post(`${BASE_URL}/api/cart/items`,
    JSON.stringify({ storeId: STORE_ID, menuId: MENU_ID, quantity: 1, selectedOptions: [] }),
    { headers: hdr });
  check(addRes, { '장바구니 담기 성공': (r) => r.status === 200 });

  const cartRes = http.get(`${BASE_URL}/api/cart`, { headers: hdr });
  const expectedAmount = cartRes.json('data.totalPrice');

  console.log(`\n준비 완료: user=${userId}, 재고 무제한, 카트 totalPrice=${expectedAmount}`);
  console.log(`→ 같은 결제를 ${VU_COUNT}회 동시 전송 시작 (기대: 1건만 처리, 나머지 중복 거절)\n`);
  return { token, expectedAmount };
}

// ── 메인: 동일 결제 동시 전송 ─────────────────────────────────────
export default function ({ token, expectedAmount }) {
  if (!token) { otherResponse.add(1); return; }

  const headers = { ...JSON_HDR, Authorization: `Bearer ${token}` };
  const res = http.post(`${BASE_URL}/api/payments`,
    JSON.stringify({
      method:          'CREDIT_CARD',
      storeId:         STORE_ID,
      orderType:       'TAKEOUT',
      expectedAmount:  expectedAmount,
      customerRequest: null,
    }),
    { headers });

  if (res.status === 200) {
    paymentSuccess.add(1);
  } else if (res.status === 409) {
    duplicateReject.add(1);          // 멱등성 중복 거절 — 기대 동작
  } else if (res.status === 402) {
    paymentMockFail.add(1);          // 처리는 됐으나 Mock PG 실패
  } else {
    otherResponse.add(1);
    console.log(`VU${__VU} 기타 응답: status=${res.status} body=${res.body}`);
  }
}

// ── teardown: 실제 생성된 주문 수로 중복 여부 교차 검증 ───────────
export function teardown(data) {
  if (!data.token) {
    console.log(`\n❌ 검증 불가: setup 에서 토큰 발급 실패\n`);
    return;
  }
  // 비동기 주문 처리 정착 대기
  sleep(3);

  const hdr = { ...JSON_HDR, Authorization: `Bearer ${data.token}` };
  const res = http.get(`${BASE_URL}/api/orders`, { headers: hdr });
  const orders = res.status === 200 ? (res.json('data') || []) : [];
  const total = orders.length;
  const succeeded = orders.filter((o) => o.status && o.status !== 'CANCELLED').length;

  console.log(`\n========================================`);
  console.log(`이 유저가 생성한 주문 수:  ${total}건`);
  console.log(`  └ 성공(취소 아님):       ${succeeded}건`);
  console.log(`----------------------------------------`);
  if (succeeded <= 1 && total <= 1) {
    console.log(`✅ 멱등성 정상 — 동일 결제 ${VU_COUNT}회 동시 전송에도 주문은 ${total}건뿐 (중복 결제 차단)`);
  } else if (succeeded <= 1) {
    console.log(`✅ 성공 주문 ${succeeded}건 (중복 결제 없음). 단 취소 주문 포함 ${total}건 — Mock PG 실패 재시도 흔적일 수 있음`);
  } else {
    console.log(`💥 중복 결제 발생: 성공 주문이 ${succeeded}건 → 멱등성 실패`);
  }
  console.log(`→ payment_success / duplicate_reject(409) / payment_mock_fail / other 메트릭으로 응답 분포 확인`);
  console.log(`========================================\n`);
}

// ── 결과 요약 ─────────────────────────────────────────────────────
export function handleSummary(data) {
  const ok   = data.metrics.payment_success?.values.count  ?? 0;
  const dup  = data.metrics.duplicate_reject?.values.count ?? 0;
  const mock = data.metrics.payment_mock_fail?.values.count ?? 0;
  const etc  = data.metrics.other_response?.values.count   ?? 0;
  console.log(`\n========================================`);
  console.log(`💳 처리 성공(200):        ${ok}건`);
  console.log(`🟡 Mock PG 실패(402):     ${mock}건  → 처리는 됨(키 점유)`);
  console.log(`🔁 중복 거절(409):        ${dup}건  → 멱등성 차단 (기대값 ${VU_COUNT - 1} 근처)`);
  console.log(`⚪ 기타(400 빈카트 등):   ${etc}건`);
  console.log(`→ 처리(200+402)는 1건이어야 정상. 주문 수 검증은 위 teardown 로그 참고`);
  console.log(`========================================\n`);
  return {};
}
