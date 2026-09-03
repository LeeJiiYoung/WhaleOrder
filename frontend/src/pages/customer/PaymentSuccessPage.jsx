import {useEffect, useState} from 'react'
import {useNavigate, useSearchParams} from 'react-router-dom'
import {confirmPayment} from '../../api/payment'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './PaymentResultPage.module.css'

/**
 * 토스 결제 인증 성공 리다이렉트 페이지. (@route /success)
 *
 * 토스가 쿼리스트링(paymentKey·orderId·amount)을 붙여 이 페이지로 리다이렉트하지만,
 * 이 시점엔 아직 "인증"만 끝난 상태라 결제가 확정된 게 아니다. 그 값들을 그대로
 * 서버 승인(confirm) API에 넘겨야 실제 승인이 이뤄진다 — confirm이 성공해야 결제 완료.
 */
export default function PaymentSuccessPage() {
    const navigate = useNavigate()
    const [searchParams] = useSearchParams()
    const [status, setStatus] = useState('confirming') // confirming | done | failed
    const [error, setError] = useState('')
    const [order, setOrder] = useState(null)

    const paymentKey = searchParams.get('paymentKey')
    const orderId = searchParams.get('orderId')
    const amount = searchParams.get('amount')

    useEffect(() => {
        if (!paymentKey || !orderId || !amount) {
            setStatus('failed')
            setError('결제 정보가 올바르지 않습니다.')
            return
        }
        confirmPayment({paymentKey, orderId, amount: Number(amount)})
            .then((res) => {
                setOrder(res.data.data)
                setStatus('done')
            })
            .catch((err) => {
                setStatus('failed')
                setError(err.response?.data?.message || '결제 승인 처리 중 문제가 발생했습니다.')
            })
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    return (
        <CustomerLayout>
            <div className={styles.page}>
                <div className={styles.card}>
                    {status === 'confirming' && (
                        <>
                            <div className={styles.icon}>⏳</div>
                            <h1 className={styles.title}>결제 확인 중...</h1>
                            <p className={styles.desc}>잠시만 기다려주세요.</p>
                        </>
                    )}

                    {status === 'done' && (
                        <>
                            <div className={styles.icon}>✅</div>
                            <h1 className={styles.title}>결제가 완료됐어요</h1>
                            <p className={styles.desc}>주문이 정상적으로 접수됐습니다.</p>

                            <div className={styles.summary}>
                                <div className={styles.summaryRow}>
                                    <span className={styles.summaryLabel}>주문번호</span>
                                    <span className={styles.summaryValue}>{order?.orderId}</span>
                                </div>
                                <div className={styles.summaryRow}>
                                    <span className={styles.summaryLabel}>결제 금액</span>
                                    <span className={styles.summaryValue}>{Number(order?.amount).toLocaleString()}원</span>
                                </div>
                            </div>

                            <button
                                className={styles.primaryBtn}
                                onClick={() => navigate(`/orders/${order?.orderId}`)}
                            >
                                주문 상세 보기
                            </button>
                            <button className={styles.secondaryBtn} onClick={() => navigate('/stores')}>
                                홈으로
                            </button>
                        </>
                    )}

                    {status === 'failed' && (
                        <>
                            <div className={styles.icon}>⚠️</div>
                            <h1 className={styles.title}>결제 승인에 실패했어요</h1>
                            <p className={styles.desc}>
                                {error} 이미 결제가 진행됐을 수 있으니, 다시 결제하기 전에 주문 내역을 먼저 확인해주세요.
                            </p>
                            <button className={styles.primaryBtn} onClick={() => navigate('/my-orders')}>
                                주문 내역 확인하기
                            </button>
                            <button className={styles.secondaryBtn} onClick={() => navigate('/cart')}>
                                장바구니로 돌아가기
                            </button>
                        </>
                    )}
                </div>
            </div>
        </CustomerLayout>
    )
}
