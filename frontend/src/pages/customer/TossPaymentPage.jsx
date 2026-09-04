import {useState, useEffect, useRef} from 'react'
import {useNavigate, useLocation} from 'react-router-dom'
import {preparePayment} from '../../api/payment'
import CustomerLayout from '../../components/customer/CustomerLayout'
import {loadTossPayments, ANONYMOUS} from "@tosspayments/tosspayments-sdk";

import styles from './PaymentPage.module.css'

const clientKey = "test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm";
const customerKey = "FRq0BgMFf9tykXUrcaekD";

const ORDER_TYPE_LABEL = {TAKEOUT: '포장', DINE_IN: '매장 내 취식'}

// widgets.requestPayment()가 결제창을 열기 전 클라이언트 단에서 막을 때 던지는 토스 SDK 에러 코드.
// 참고: https://docs.tosspayments.com/sdk/v2/error-codes
const TOSS_SDK_ERROR_MESSAGE = {
    NEED_AGREEMENT_WITH_REQUIRED_TERMS: '필수 약관에 동의해주세요.',
    USER_CANCEL: '결제를 취소했어요.',
}

/**
 * 고객 결제 페이지. (@route /toss-payment)
 *
 * 진입 시 서버에 결제 준비(prepare)를 요청해 orderId·금액을 미리 저장해두고,
 * 그 응답(orderId·amount·orderName)으로만 토스 결제창을 연다.
 * 결제 버튼은 prepare 응답이 오기 전까지 비활성화된다.
 */
export default function TossPaymentPage() {
    const navigate = useNavigate()

    const [ready, setReady] = useState(false);
    const [widgets, setWidgets] = useState(null);
    const {state} = useLocation()

    const {orderType, customerRequest, totalPrice, totalCount, items} = state

    const [amount, setAmount] = useState({
        currency: "KRW",
        value: totalPrice,
    });

    const storeId = localStorage.getItem('selectedStoreId')
    const storeName = localStorage.getItem('selectedStoreName')
    // CustomerLayout과 동일한 방식으로 로그인 사용자 닉네임을 읽는다 (로그인 시 저장됨)
    const nickname = localStorage.getItem('nickname') || '고객'

    // 서버 prepare 응답 — 실제 결제창 호출(orderId·orderName·금액)은 전부 이 값만 사용한다
    const [prepared, setPrepared] = useState(null)
    const [prepareError, setPrepareError] = useState('')

    const [paying, setPaying] = useState(false)
    const [error, setError] = useState('')

    // React StrictMode(개발 모드)는 useEffect를 마운트 시 일부러 두 번 실행한다.
    // 가드 없이 두면 prepare 요청이 거의 동시에 두 번 나가서, 서버의 멱등성 락에
    // 뒤늦게 걸린 두 번째 요청이 "동일한 요청이 처리 중입니다" 에러를 내고 그게
    // 먼저 온 성공 응답을 덮어써버린다. ref로 실제 요청은 한 번만 나가게 막는다.
    const preparedOnceRef = useRef(false)

    // ------ 결제 준비: orderId·금액을 서버에 임시 저장 ------
    useEffect(() => {
        if (!storeId) return
        if (preparedOnceRef.current) return
        preparedOnceRef.current = true

        preparePayment({
            storeId: Number(storeId),
            orderType,
            expectedAmount: totalPrice,
            customerRequest,
        })
            .then((res) => setPrepared(res.data.data))
            .catch((err) => {
                setPrepareError(err.response?.data?.message || '주문을 준비하지 못했습니다. 장바구니를 다시 확인해주세요.')
            })
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    // prepare 응답이 오면 서버가 확정한 금액으로 위젯 금액을 맞춘다
    useEffect(() => {
        if (!prepared) return
        setAmount({currency: "KRW", value: prepared.amount})
    }, [prepared])

    useEffect(() => {

        async function fetchPaymentWidgets() {
            // ------  결제위젯 초기화 ------
            const tossPayments = await loadTossPayments(clientKey);
            // 회원 결제
            const widgets = tossPayments.widgets({
                customerKey,
            });
            // 비회원 결제
            // const widgets = tossPayments.widgets({ customerKey: ANONYMOUS });

            setWidgets(widgets);
        }

        fetchPaymentWidgets();
    }, [clientKey, customerKey])

    useEffect(() => {
        async function renderPaymentWidgets() {
            if (widgets == null) {
                return;
            }
            // ------ 주문의 결제 금액 설정 ------
            await widgets.setAmount(amount);

            await Promise.all([
                // ------  결제 UI 렌더링 ------
                widgets.renderPaymentMethods({
                    selector: "#payment-method",
                    variantKey: "DEFAULT",
                }),
                // ------  이용약관 UI 렌더링 ------
                widgets.renderAgreement({
                    selector: "#agreement",
                    variantKey: "AGREEMENT",
                }),
            ]);

            setReady(true);
        }

        renderPaymentWidgets();
    }, [widgets]);

    useEffect(() => {
        if (widgets == null) {
            return;
        }

        widgets.setAmount(amount);
    }, [widgets, amount]);

    if (!state || !storeId) {
        navigate('/cart', {replace: true})
        return null
    }

    const handlePay = async () => {
        if (!prepared) return
        setPaying(true)
        setError('')
        try {
            await widgets.requestPayment({
                orderId: prepared.tossOrderId,
                orderName: prepared.orderName,
                successUrl: window.location.origin + "/success",
                failUrl: window.location.origin + "/fail",
                customerName: nickname,
            })
        } catch (err) {
            // widgets.requestPayment()가 던지는 건 토스 SDK 에러(err.code/err.message)라
            // 우리 백엔드 axios 에러 형식(err.response.data.message)과 다르다.
            // 결제창이 열리기 전 클라이언트 단에서 걸러지는 대표 케이스만 메시지를 따로 안내한다.
            setError(TOSS_SDK_ERROR_MESSAGE[err.code] || err.message || '결제에 실패했습니다. 다시 시도해주세요.')
        } finally {
            setPaying(false)
        }
    }

    const canPay = ready && !!prepared && !paying

    return (
        <CustomerLayout>
            <div className={styles.page}>
                <h1 className={styles.title}>결제하기</h1>

                {/* 주문 요약 — 토스 위젯 마운트 지점(#payment-method)과 분리된 별도 카드 */}
                <section className={styles.card}>
                    <h2 className={styles.sectionTitle}>주문 정보</h2>
                    <div className={styles.infoRow}>
                        <span className={styles.infoLabel}>매장</span>
                        <span className={styles.infoValue}>{storeName}</span>
                    </div>
                    <div className={styles.infoRow}>
                        <span className={styles.infoLabel}>주문 방식</span>
                        <span className={styles.infoValue}>{ORDER_TYPE_LABEL[orderType]}</span>
                    </div>
                    {items?.length > 0 && (
                        <div className={styles.infoRow}>
                            <span className={styles.infoLabel}>주문 상품</span>
                            <span className={styles.infoValueList}>
                                {items.map((it, idx) => (
                                    <span key={idx}>{it.menuName}{it.quantity > 1 ? ` x${it.quantity}` : ''}</span>
                                ))}
                            </span>
                        </div>
                    )}
                    {customerRequest && (
                        <div className={styles.infoRow}>
                            <span className={styles.infoLabel}>요청사항</span>
                            <span className={styles.infoValue}>{customerRequest}</span>
                        </div>
                    )}
                    <div className={styles.infoRow}>
                        <span className={styles.infoLabel}>상품 수</span>
                        <span className={styles.infoValue}>{totalCount}개</span>
                    </div>
                    <div className={`${styles.infoRow} ${styles.infoRowTotal}`}>
                        <span className={styles.infoLabel}>합계</span>
                        <span className={styles.totalPrice}>{totalPrice?.toLocaleString()}원</span>
                    </div>
                </section>

                <div className={styles.mockNotice}>
                    ℹ️ 토스페이먼츠 테스트 결제입니다. 실제 결제는 이루어지지 않습니다.
                </div>

                {/* 결제 수단 UI — 토스 SDK가 이 안에 직접 렌더링한다 (다른 콘텐츠를 넣지 않는다) */}
                <div id="payment-method"/>
                {/* 이용약관 UI */}
                <div id="agreement"/>

                {prepareError && <div className={styles.errorBox}>{prepareError}</div>}
                {error && <div className={styles.errorBox}>{error}</div>}

                <button className={styles.payBtn} onClick={handlePay} disabled={!canPay}>
                    {!prepared
                        ? (prepareError ? '주문 준비 실패' : '주문 준비 중...')
                        : paying
                            ? '결제 처리 중...'
                            : `${totalPrice?.toLocaleString()}원 결제하기`}
                </button>
                <button className={styles.backBtn} onClick={() => navigate('/cart')}>
                    장바구니로 돌아가기
                </button>
            </div>
        </CustomerLayout>
    )
}
