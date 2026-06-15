import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// 커스텀 메트릭
const orderSuccess   = new Counter('order_success');
const orderFail      = new Counter('order_fail');
const orderFailRate  = new Rate('order_fail_rate');
const orderDuration  = new Trend('order_duration_ms', true);

// ── 테스트 시나리오 설정 ───────────────────────────────────────────
// 실행: docker run --rm -i grafana/k6 run --out experimental-prometheus-rw - < k6/order-load-test.js
// 환경변수: K6_PROMETHEUS_RW_SERVER_URL=http://host.docker.internal:9090/api/v1/write
export const options = {
  scenarios: {
    // 시나리오 1: 점진적 부하 (일반 성능 측정)
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 10  },  // 30초 동안 10명까지 증가
        { duration: '1m',  target: 50  },  // 1분 동안 50명 유지
        { duration: '30s', target: 0   },  // 30초 동안 0명으로 감소
      ],
    },
  },
  thresholds: {
    http_req_duration:  ['p(95)<2000'],   // 95%가 2초 이내
    http_req_failed:    ['rate<0.05'],    // 실패율 5% 미만
    order_fail_rate:    ['rate<0.1'],     // 주문 실패율 10% 미만
  },
};

const BASE_URL = 'http://host.docker.internal:8080';

// ── 로그인해서 토큰 발급 ──────────────────────────────────────────
function login(email, password) {
  const res = http.post(`${BASE_URL}/api/auth/login`,
    JSON.stringify({ userId: email, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(res, { '로그인 성공': (r) => r.status === 200 });
  return res.json('data.accessToken');
}

// ── 메인 시나리오 ─────────────────────────────────────────────────
export default function () {
  // 테스트 계정 (사전에 생성 필요)
  const token = login('testuser', 'test1234');
  if (!token) return;

  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`,
  };

  // 1. 메뉴 조회 (캐시 성능 측정)
  const menuRes = http.get(`${BASE_URL}/api/stores/1/menus`);
  check(menuRes, { '메뉴 조회 성공': (r) => r.status === 200 });
  sleep(0.5);

  // 2. 장바구니 초기화 후 메뉴 추가
  http.del(`${BASE_URL}/api/cart`, null, { headers });

  const addCartRes = http.post(`${BASE_URL}/api/cart/items`,
    JSON.stringify({ menuId: 1, quantity: 1, selectedOptions: [] }),
    { headers }
  );
  check(addCartRes, { '장바구니 추가 성공': (r) => r.status === 200 });
  sleep(0.3);

  // 3. 주문 생성 (핵심 - 동시성 테스트)
  const start = Date.now();
  const orderRes = http.post(`${BASE_URL}/api/orders`,
    JSON.stringify({ storeId: 1, orderType: 'TAKEOUT', customerRequest: '' }),
    { headers }
  );
  orderDuration.add(Date.now() - start);

  const ok = check(orderRes, { '주문 생성 성공': (r) => r.status === 200 || r.status === 201 || r.status === 202 });
  if (ok) {
    orderSuccess.add(1);
    orderFailRate.add(false);
  } else {
    orderFail.add(1);
    orderFailRate.add(true);
    console.log(`주문 실패: status=${orderRes.status} body=${orderRes.body}`);
  }

  sleep(1);
}
