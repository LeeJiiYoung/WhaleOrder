import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 재고 차감 동시성 테스트
// 목적: 재고 N개짜리 메뉴에 100명이 동시에 주문 → N건만 성공해야 함 (분산락 검증)

const orderSuccess = new Counter('order_success');
const orderFail    = new Counter('order_fail');

const VU_COUNT  = 20;   // 동시 주문 유저 수 (계정 생성 부담 감안해서 20명으로)
const MENU_ID   = 1;    // 아메리카노
const STORE_ID  = 1;

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
const JSON_HDR  = { 'Content-Type': 'application/json' };

// ── setup: 테스트 계정 생성 + 장바구니 세팅 ────────────────────────
export function setup() {
  // 어드민 토큰 발급
  const adminRes = http.post(`${BASE_URL}/api/auth/login`,
    JSON.stringify({ userId: 'admin', password: 'adminadmin' }), { headers: JSON_HDR });
  const adminToken = adminRes.json('data.accessToken');
  const adminHdr   = { ...JSON_HDR, Authorization: `Bearer ${adminToken}` };

  const tokens = [];

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

    if (token) {
      const hdr = { ...JSON_HDR, Authorization: `Bearer ${token}` };
      // 장바구니 초기화 후 아이템 1개 담기
      http.del(`${BASE_URL}/api/cart`, null, { headers: hdr });
      http.post(`${BASE_URL}/api/cart/items`,
        JSON.stringify({ menuId: MENU_ID, quantity: 1, selectedOptions: [] }),
        { headers: hdr }
      );
    }
    tokens.push(token || '');
  }

  console.log(`\n준비 완료: ${tokens.filter(Boolean).length}개 계정 토큰 발급`);
  console.log(`→ 어드민에서 storeId=${STORE_ID} menuId=${MENU_ID} 재고를 10개로 설정 후 테스트하세요\n`);
  return { tokens };
}

// ── 메인: 동시 주문 ───────────────────────────────────────────────
export default function ({ tokens }) {
  const token = tokens[__VU - 1];
  if (!token) { orderFail.add(1); return; }

  const headers = { ...JSON_HDR, Authorization: `Bearer ${token}` };

  const res = http.post(`${BASE_URL}/api/orders`,
    JSON.stringify({ storeId: STORE_ID, orderType: 'TAKEOUT', customerRequest: '' }),
    { headers }
  );

  if (res.status === 200 || res.status === 201 || res.status === 202) {
    orderSuccess.add(1);
  } else {
    orderFail.add(1);
    console.log(`VU${__VU} 주문 실패: status=${res.status} body=${res.body}`);
  }
}

// ── 결과 요약 ─────────────────────────────────────────────────────
export function handleSummary(data) {
  const success = data.metrics.order_success?.values.count ?? 0;
  const fail    = data.metrics.order_fail?.values.count    ?? 0;
  console.log(`\n========================================`);
  console.log(`✅ 주문 성공: ${success}건`);
  console.log(`❌ 주문 실패: ${fail}건`);
  console.log(`→ 재고 10개 기준: 성공 10건 = 분산락 정상`);
  console.log(`→ 성공 > 10건   = 분산락 오류 (초과 차감)`);
  console.log(`========================================\n`);
  return {};
}
