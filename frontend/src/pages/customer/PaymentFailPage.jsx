import {useNavigate, useSearchParams} from 'react-router-dom'
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
 * 이 단계는 서버에 아무 것도 확정되지 않은 상태 — prepare 때 만든 Payment는 여전히 PENDING이라
 * /cart에서 다시 결제를 시도하면 된다.
 */
export default function PaymentFailPage() {
    const navigate = useNavigate()
    const [searchParams] = useSearchParams()

    const code = searchParams.get('code')
    const message = searchParams.get('message')

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
