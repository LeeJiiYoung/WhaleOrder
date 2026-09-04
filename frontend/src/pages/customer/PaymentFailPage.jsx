import {useEffect, useRef} from 'react'
import {useNavigate, useSearchParams} from 'react-router-dom'
import {cancelPendingPayment} from '../../api/payment'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './PaymentResultPage.module.css'

// 토스 결제창에서 자주 나오는 실패 코드에 대한 한글 안내 — 목록에 없는 코드는 토스가 준 message를 그대로 보여준다
const CODE_MESSAGE = {
    PAY_PROCESS_CANCELED: '결제를 취소하셨어요.',
    PAY_PROCESS_ABORTED: '결제 진행 중 오류가 발생했어요.',
    REJECT_CARD_COMPANY: '카드사에서 결제를 거절했어요.',
}

/**
 * 토스 결제 실패/취소 리다이렉트 페이지. (@route /fail)
 *
 * confirm()이 아예 호출되지 않았으므로 prepare 때 만든 주문은 여전히 AWAITING_PAYMENT(결제 대기)
 * 상태로 남아있다 — 그대로 두면 결제도 안 됐는데 주문만 붕 떠버리므로, 진입 즉시 서버에 정리를
 * 요청한다(cancelPendingPayment). 실패해도 화면 표시엔 영향 없음 — 최종 안전망은 서버 스케줄러가
 * 맡는다. 정리 후 사용자는 /cart에서 다시 결제를 시도하면 된다.
 */
export default function PaymentFailPage() {
    const navigate = useNavigate()
    const [searchParams] = useSearchParams()

    const code = searchParams.get('code')
    const message = searchParams.get('message')
    const orderId = searchParams.get('orderId')

    // React StrictMode(개발 모드)의 effect 이중 실행 방지 — 다른 결제 페이지들과 동일한 패턴
    const cleanedUpOnceRef = useRef(false)

    useEffect(() => {
        if (cleanedUpOnceRef.current) return
        cleanedUpOnceRef.current = true

        if (!orderId) return
        cancelPendingPayment({orderId}).catch((err) => {
            // 정리 실패는 화면에 노출하지 않는다 — 사용자는 이미 실패 안내를 보고 있고,
            // 방치된 주문은 서버 스케줄러(PaymentSweepScheduler)가 나중에 정리한다.
            console.error('결제 대기 주문 정리 실패', err)
        })
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    return (
        <CustomerLayout>
            <div className={styles.page}>
                <div className={styles.card}>
                    <div className={styles.icon}>✖️</div>
                    <h1 className={styles.title}>결제에 실패했어요</h1>
                    <p className={styles.desc}>{message || CODE_MESSAGE[code] || '결제가 완료되지 않았습니다.'}</p>
                    {code && <p className={styles.codeHint}>오류 코드: {code}</p>}

                    <button className={styles.primaryBtn} onClick={() => navigate('/cart')}>
                        장바구니로 돌아가기
                    </button>
                </div>
            </div>
        </CustomerLayout>
    )
}
