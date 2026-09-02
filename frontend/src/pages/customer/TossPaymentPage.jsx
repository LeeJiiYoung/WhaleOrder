import {useState, useEffect} from 'react'
import {useNavigate, useLocation} from 'react-router-dom'
import {processPayment} from '../../api/payment'
import CustomerLayout from '../../components/customer/CustomerLayout'
import {loadTossPayments, ANONYMOUS} from "@tosspayments/tosspayments-sdk";

import styles from './PaymentPage.module.css'

const clientKey = "test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm";
const customerKey = "FRq0BgMFf9tykXUrcaekD";

const ORDER_TYPE_LABEL = {TAKEOUT: '포장', DINE_IN: '매장 내 취식'}

/**
 * 고객 결제 페이지. (@route /toss-payment)
 *
 * 토스 연동 해봄
 */
export default function TossPaymentPage() {
    const navigate = useNavigate()


    const [ready, setReady] = useState(false);

    const [widgets, setWidgets] = useState(null);
    const {state} = useLocation()

    const {orderType, customerRequest, totalPrice, totalCount} = state

    const [amount, setAmount] = useState({
        currency: "KRW",
        value: 5000,
    });

    const storeId = localStorage.getItem('selectedStoreId')
    const storeName = localStorage.getItem('selectedStoreName')

    const [method, setMethod] = useState('CREDIT_CARD')
    const [paying, setPaying] = useState(false)
    const [error, setError] = useState('')
    const [savedCard, setSavedCard] = useState(null)
    const [useSaved, setUseSaved] = useState(false)
    const [saveCard, setSaveCard] = useState(false)
    const [cardNumber, setCardNumber] = useState('')
    const [expiry, setExpiry] = useState('')
    const [cvc, setCvc] = useState('')
    const [holderName, setHolderName] = useState('')

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
        try {
            await widgets.requestPayment({
                orderId: "JOFZzmZwWyypKGbIwSPDI",
                orderName: "토스 티셔츠 외 2건",
                successUrl: window.location.origin + "/success",
                failUrl: window.location.origin + "/fail",
                customerEmail: "customer123@gmail.com",
                customerName: "김토스",
                customerMobilePhone: "01012341234",
            })
        } catch (err) {
            setError(err.response?.data?.message || '결제에 실패했습니다. 다시 시도해주세요.')
        } finally {
            setPaying(false)
        }
    }

    return (
        <CustomerLayout>
            <div className={styles.page}>
                <h1 className={styles.title}>결제하기</h1>
                {/* 주문 요약 */}
                <div id="payment-method">
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
                </div>
                {/* 이용약관 UI */}
                <div id="agreement"/>
                <div className={styles.mockNotice}>
                    ℹ️ 테스트(Mock) 결제입니다. 실제 금액이 청구되지 않습니다.
                </div>

                {error && <div className={styles.errorBox}>{error}</div>}

                <button className={styles.payBtn} onClick={handlePay} disabled={paying}>
                    {paying ? '결제 처리 중...' : `${totalPrice?.toLocaleString()}원 결제하기`}
                </button>
                <button className={styles.backBtn} onClick={() => navigate('/cart')}>
                    장바구니로 돌아가기
                </button>
            </div>
        </CustomerLayout>
    )
}
