import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// 커스텀 메트릭
const paymentSuccess    = new Counter('payment_success');         // 200 (Mock PG 결제 성공)
const paymentMockFailed = new Counter('payment_mock_failed');     // 402 (Mock PG 의도된 10% 실패 — 시스템 정상)
const systemError       = new Counter('system_error');            // 그 외 (5xx, 400/401/403 등)
const paymentFailRate   = new Rate('payment_fail_rate');          // 시스템 에러만 실패로 카운트
const paymentDuration   = new Trend('payment_duration_ms', true);

// ── 테스트 시나리오 설정 ───────────────────────────────────────────
// 실행 (Windows / Mac Docker Desktop):
//   docker run --rm -i \
//     -e K6_PROMETHEUS_RW_SERVER_URL=http://host.docker.internal:9090/api/v1/write \
//     grafana/k6 run --out experimental-prometheus-rw - < k6/order-load-test.js
//
// Linux 호스트면 `--add-host=host.docker.internal:host-gateway` 옵션 추가
// 사전 준비:
//   - data.sql 의 testuser1 ~ testuser50 (비밀번호 adminadmin) 계정 시드
//   - 메뉴 8 "테스트 음료" (옵션 없음, 4000원) + 매장 1·2·3 의 메뉴 8 재고 무제한(-1)
//   - VU 별로 다른 계정 사용 → 멱등성 키가 memberId 를 포함하므로 진짜 동시성 시뮬레이션
export const options = {
  scenarios: {
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 10 },   // 30초 동안 10명까지 증가
        { duration: '1m',  target: 50 },   // 1분 동안 50명 유지
        { duration: '30s', target: 0  },   // 30초 동안 0명으로 감소
      ],
    },
  },
  thresholds: {
    http_req_duration:   ['p(95)<2000'],   // 95%가 2초 이내
    payment_fail_rate:   ['rate<0.05'],    // 시스템 에러율 5% 미만 (Mock 10% 실패는 별도)
    payment_duration_ms: ['p(95)<2000'],
  },
};

const BASE_URL = 'http://host.docker.internal:8080';
const STORE_ID = 1;
const MENU_ID  = 8;   // 옵션 없는 부하 테스트 전용 메뉴

// ── 로그인 ─────────────────────────────────────────────────────────
function login(userId, password) {
  const res = http.post(`${BASE_URL}/api/auth/login`,
    JSON.stringify({ userId, password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } }
  );
  check(res, { '로그인 성공': (r) => r.status === 200 });
  if (res.status !== 200) {
    console.log(`로그인 실패: vu=${__VU} status=${res.status} body=${res.body}`);
    return null;
  }
  return res.json('data.accessToken');
}

// ── 메인 시나리오 ─────────────────────────────────────────────────
export default function () {
  // VU 별로 다른 계정 — testuser1 ~ testuser50 (data.sql 시드)
  const userId = `testuser${__VU}`;
  const token  = login(userId, 'adminadmin');
  if (!token) return;

  const headers = {
    'Content-Type':  'application/json',
    'Authorization': `Bearer ${token}`,
  };

  // 1. 이전 카트 잔여 항목 정리 (반복 실행 안전성)
  http.del(`${BASE_URL}/api/cart`, null, { headers, tags: { name: 'cart_clear' } });

  // 2. 장바구니에 메뉴 담기 — storeId 포함 필수
  const addCartRes = http.post(`${BASE_URL}/api/cart/items`,
    JSON.stringify({ storeId: STORE_ID, menuId: MENU_ID, quantity: 1, selectedOptions: [] }),
    { headers, tags: { name: 'cart_add' } }
  );
  if (!check(addCartRes, { '장바구니 추가 성공': (r) => r.status === 200 })) {
    console.log(`카트 추가 실패: vu=${__VU} status=${addCartRes.status} body=${addCartRes.body}`);
    systemError.add(1);
    paymentFailRate.add(true);
    return;
  }

  // 3. 카트 조회 — expectedAmount 추출 (서버 totalPrice 와 일치해야 결제 진행)
  const cartRes = http.get(`${BASE_URL}/api/cart`, { headers, tags: { name: 'cart_get' } });
  if (cartRes.status !== 200) {
    console.log(`카트 조회 실패: vu=${__VU} status=${cartRes.status}`);
    systemError.add(1);
    paymentFailRate.add(true);
    return;
  }
  const expectedAmount = cartRes.json('data.totalPrice');

  // 4. 결제 (핵심 — 동시성 / 멱등성 / 분산 락 모두 여기서 측정됨)
  const start = Date.now();
  const payRes = http.post(`${BASE_URL}/api/payments`,
    JSON.stringify({
      method:          'CREDIT_CARD',
      storeId:         STORE_ID,
      orderType:       'TAKEOUT',
      expectedAmount:  expectedAmount,
      customerRequest: null,
    }),
    { headers, tags: { name: 'payment' } }
  );
  paymentDuration.add(Date.now() - start);

  // 응답 분류 — Mock PG 실패(402) 는 시스템 정상 동작이므로 따로 카운트
  if (payRes.status === 200) {
    paymentSuccess.add(1);
    paymentFailRate.add(false);
  } else if (payRes.status === 402) {
    paymentMockFailed.add(1);
    paymentFailRate.add(false);   // Mock 실패는 의도된 동작 → 시스템 실패율에 안 잡힘
  } else {
    systemError.add(1);
    paymentFailRate.add(true);
    console.log(`시스템 에러: vu=${__VU} status=${payRes.status} body=${payRes.body}`);
  }

  sleep(1);
}
