import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 재고 차감 동시성 테스트 (선결제 + 비동기 재고 차감 구조에 맞게 갱신)
// docker run --rm -i grafana/k6 run - < k6/stock-concurrency-test.js
// ⚠️ 흐름 변경 안내:
//   예전: POST /api/orders 가 동기적으로 재고를 차감 → HTTP 응답으로 성공/실패 판별 가능
//   현재: POST /api/payments(선결제) 200 → AFTER_COMMIT → Kafka → OrderProcessingService 에서 재고 차감
//         즉 결제 200 != 재고 차감 성공. 재고 차감은 비동기로 나중에 일어남.
//   따라서 "N건만 성공" 검증은 HTTP 응답이 아니라 테스트 종료 후 실제 잔여 재고로 한다.
//
// 목적: 재고 N개짜리 메뉴에 VU_COUNT 명이 동시에 결제 → 비동기 차감 후에도
//       오버셀(재고 < 0) 없이 정확히 N건만 차감돼야 함 (분산락 검증)

const paymentSuccess    = new Counter('payment_success');       // 200 (결제 성공 → 재고 차감 시도 대상)
const paymentMockFailed = new Counter('payment_mock_failed');   // 402 (Mock PG 의도된 10% 실패 — 재고 차감 안 함)
const systemError       = new Counter('system_error');          // 그 외 (5xx, 400 등)

const VU_COUNT      = 20;   // 동시 결제 유저 수
const INITIAL_STOCK = 10;   // 테스트 시작 시 세팅할 재고 (이 수만큼만 차감돼야 정상)
const MENU_ID       = 8;    // 테스트 음료 — 필수 옵션 없는 메뉴(빈 selectedOptions 로 담기 가능). 메뉴 1(아메리카노)은 SIZE·TEMPERATURE 필수
const STORE_ID      = 1;

export const options = {
  scenarios: {
    spike: {
      executor: 'per-vu-iterations',
      vus: VU_COUNT,
      iterations: 1,
      maxDuration: '60s',
    },
  },
};

const BASE_URL = 'http://host.docker.internal:8080';
const JSON_HDR = { 'Content-Type': 'application/json' };

// ── setup: 재고 세팅 + 테스트 계정 생성 + 장바구니/표시금액 준비 ─────
export function setup() {
  // 어드민 토큰 발급
  const adminRes = http.post(`${BASE_URL}/api/auth/login`,
    JSON.stringify({ userId: 'admin', password: 'adminadmin' }), { headers: JSON_HDR });
  const adminToken = adminRes.json('data.accessToken');
  if (!adminToken) {
    // 어드민 인증 실패 시 재고 세팅·계정 생성이 전부 무력화됨 → 테스트 의미 없음
    console.log(`❌ 어드민 로그인 실패 status=${adminRes.status} body=${adminRes.body}`);
    console.log(`   → 서버가 data.sql 시드(admin/adminadmin)로 떠 있는지 확인 필요`);
  }
  const adminHdr   = { ...JSON_HDR, Authorization: `Bearer ${adminToken}` };

  // 재고를 INITIAL_STOCK 으로 설정 (테스트 결정성 확보 — 수동 세팅 불필요)
  const setStockRes = http.put(`${BASE_URL}/api/admin/stores/${STORE_ID}/stocks/${MENU_ID}`,
    JSON.stringify({ quantity: INITIAL_STOCK }), { headers: adminHdr });
  check(setStockRes, { '재고 세팅 성공': (r) => r.status === 200 });
  if (setStockRes.status !== 200) {
    console.log(`재고 세팅 실패: status=${setStockRes.status} body=${setStockRes.body}`);
  }

  // VU 별 토큰 + 표시 금액(expectedAmount) 준비
  const users = [];

  for (let i = 1; i <= VU_COUNT; i++) {
    const userId = `loadtest${i}`;
    const pw     = 'loadtest1';

    // 계정 생성 (이미 있으면 무시)
    http.post(`${BASE_URL}/api/admin/members`,
      JSON.stringify({ userId, password: pw, name: userId, nickname: userId, role: 'CUSTOMER' }),
      { headers: adminHdr }
    );

    // 로그인해서 토큰 발급
    const loginRes = http.post(`${BASE_URL}/api/auth/login`,
      JSON.stringify({ userId, password: pw }), { headers: JSON_HDR });
    const token = loginRes.json('data.accessToken');

    let expectedAmount = null;
    if (token) {
      const hdr = { ...JSON_HDR, Authorization: `Bearer ${token}` };
      // 장바구니 초기화 후 아이템 1개 담기 — storeId 포함 필수
      http.del(`${BASE_URL}/api/cart`, null, { headers: hdr });
      http.post(`${BASE_URL}/api/cart/items`,
        JSON.stringify({ storeId: STORE_ID, menuId: MENU_ID, quantity: 1, selectedOptions: [] }),
        { headers: hdr }
      );
      // 표시 금액 확인용 totalPrice 추출 (서버 계산값과 일치해야 결제 진행)
      const cartRes = http.get(`${BASE_URL}/api/cart`, { headers: hdr });
      expectedAmount = cartRes.json('data.totalPrice');
    }
    users.push({ token: token || '', expectedAmount });
  }

  const ready = users.filter(u => u.token).length;
  console.log(`\n준비 완료: ${ready}/${VU_COUNT}개 계정 토큰 발급, 재고 ${INITIAL_STOCK}개 세팅 시도`);
  if (ready === 0) {
    console.log(`❌ 토큰 0개 — 모든 결제가 시스템 에러로 처리됨. 어드민 인증/계정 생성 실패 원인 먼저 해결`);
  }
  console.log(`→ storeId=${STORE_ID} menuId=${MENU_ID} 에 ${VU_COUNT}명 동시 결제 시작\n`);
  return { users, adminToken, initialStock: INITIAL_STOCK };
}

// ── 메인: 동시 결제 ───────────────────────────────────────────────
export default function ({ users }) {
  const user = users[__VU - 1];
  if (!user || !user.token) {
    systemError.add(1);
    // 토큰이 없으면 결제 자체를 시도 못 함 — setup 의 계정 생성/로그인 실패가 원인
    console.log(`VU${__VU} 토큰 없음 → setup 단계 실패(어드민 인증/계정 생성/로그인). 결제 미시도`);
    return;
  }

  const headers = { ...JSON_HDR, Authorization: `Bearer ${user.token}` };

  // 선결제 — 동시성/분산락 검증의 진입점. 재고 차감은 이 응답 이후 비동기로 일어남.
  const res = http.post(`${BASE_URL}/api/payments`,
    JSON.stringify({
      method:          'CREDIT_CARD',
      storeId:         STORE_ID,
      orderType:       'TAKEOUT',
      expectedAmount:  user.expectedAmount,
      customerRequest: null,
    }),
    { headers }
  );

  // 결제 응답 분류 — Mock PG 의도된 실패(402)는 재고 차감 대상이 아님
  if (res.status === 200) {
    paymentSuccess.add(1);
  } else if (res.status === 402) {
    paymentMockFailed.add(1);
  } else {
    systemError.add(1);
    console.log(`VU${__VU} 결제 실패: status=${res.status} body=${res.body}`);
  }
}

// ── teardown: 비동기 차감이 끝난 뒤 실제 잔여 재고로 분산락 검증 ─────
export function teardown(data) {
  const adminHdr = { ...JSON_HDR, Authorization: `Bearer ${data.adminToken}` };

  // 비동기(Kafka → OrderProcessingService) 재고 차감이 정착될 때까지 대기 후 폴링
  let remaining = -1;
  for (let attempt = 1; attempt <= 10; attempt++) {
    sleep(2);
    const stockRes = http.get(`${BASE_URL}/api/stores/${STORE_ID}/stocks`, { headers: adminHdr });
    if (stockRes.status !== 200) continue;
    const stocks = stockRes.json('data') || [];
    const target = stocks.find((s) => s.menuId === MENU_ID);
    if (!target) continue;
    // unlimited(무제한)면 검증 불가 — 재고 세팅이 안 된 것
    if (target.unlimited) { remaining = null; break; }
    remaining = target.quantity;
    // 결제 성공 수만큼 차감이 다 반영됐으면 조기 종료
    if (remaining <= 0) break;
  }

  const initial   = data.initialStock;
  const deducted  = remaining === null ? null : initial - remaining;

  console.log(`\n========================================`);
  console.log(`초기 재고:        ${initial}개`);
  if (remaining === null) {
    console.log(`❌ 재고가 무제한으로 설정됨 — setup 의 재고 세팅 실패. 검증 불가`);
  } else {
    console.log(`최종 잔여 재고:   ${remaining}개`);
    console.log(`실제 차감량:      ${deducted}개`);
    console.log(`----------------------------------------`);
    if (remaining > initial || deducted < 0) {
      // 최종 재고가 초기 세팅값보다 큼 = 차감이 음수 → 정상일 수 없는 상태
      console.log(`⚠️ 검증 불가: 최종 재고(${remaining})가 초기 세팅값(${initial})보다 큼`);
      console.log(`   → setup 재고 PUT 미적용(어드민 인증 실패) 또는 결제가 한 건도 처리 안 됨. setup/결제 로그 확인 필요`);
    } else if (remaining < 0) {
      console.log(`💥 오버셀 발생: 재고가 음수 → 분산락 오류 (초과 차감)`);
    } else if (deducted > initial) {
      console.log(`💥 초과 차감: ${deducted} > ${initial} → 분산락 오류`);
    } else if (deducted === 0) {
      console.log(`⚠️ 차감 0건 — 결제가 모두 실패했을 수 있음. 결제 응답 분포(handleSummary) 확인`);
    } else {
      console.log(`✅ 차감 ${deducted}건 ≤ 초기 재고 ${initial}건, 음수 없음 → 분산락 정상`);
      console.log(`   (결제 성공분 중 ${deducted}건만 재고 차감, 나머지는 재고부족으로 주문 취소)`);
    }
  }
  console.log(`→ payment_success / payment_mock_failed / system_error 메트릭으로 결제 응답 분포 확인`);
  console.log(`========================================\n`);
}

// ── 결과 요약 ─────────────────────────────────────────────────────
export function handleSummary(data) {
  const success = data.metrics.payment_success?.values.count     ?? 0;
  const mockFail = data.metrics.payment_mock_failed?.values.count ?? 0;
  const sysErr  = data.metrics.system_error?.values.count        ?? 0;
  console.log(`\n========================================`);
  console.log(`💳 결제 성공(200):      ${success}건  → 비동기 재고 차감 시도 대상`);
  console.log(`💤 Mock PG 실패(402):   ${mockFail}건  → 의도된 실패, 재고 차감 안 함`);
  console.log(`❌ 시스템 에러(그 외):  ${sysErr}건`);
  console.log(`→ 최종 재고 검증 결과는 위 teardown 로그 참고 (분산락 정상 여부)`);
  console.log(`========================================\n`);
  return {};
}
