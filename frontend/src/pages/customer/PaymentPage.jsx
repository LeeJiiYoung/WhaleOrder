import { useState, useEffect } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { processPayment } from '../../api/payment'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './PaymentPage.module.css'

const PAYMENT_METHODS = [
  { value: 'CREDIT_CARD', label: '신용/체크카드', icon: '💳' },
  { value: 'KAKAO_PAY',   label: '카카오페이',    icon: '🟡' },
  { value: 'NAVER_PAY',   label: '네이버페이',     icon: '🟢' },
]

const ORDER_TYPE_LABEL = { TAKEOUT: '포장', DINE_IN: '매장 내 취식' }
const SAVED_KEY = 'wo_saved_card'

const fmtCard   = (v) => v.replace(/\D/g, '').slice(0, 16).replace(/(\d{4})(?=\d)/g, '$1-')
const fmtExpiry = (v) => { const d = v.replace(/\D/g, '').slice(0, 4); return d.length > 2 ? d.slice(0, 2) + '/' + d.slice(2) : d }

/**
 * 고객 결제 페이지. (@route /payment)
 *
 * - CartPage에서 location.state로 전달받은 주문 정보(orderType·customerRequest·합계) 표시
 * - 결제 수단: 신용/체크카드·카카오페이·네이버페이
 * - 카드 결제 시 카드번호(자동 하이픈)·유효기간·CVC·소유자명 입력
 * - "다음 결제할 때도 사용하기" 체크 시 카드 마지막 4자리를 localStorage에 저장
 * - 저장된 카드가 있으면 상단에 표시, 새 결제 수단 선택 가능
 * - 결제 성공 시 /orders/:orderId로 이동 (Mock 결제 — 실제 청구 없음)
 */
export default function PaymentPage() {
  const navigate = useNavigate()
  const { state } = useLocation()

  const storeId   = localStorage.getItem('selectedStoreId')
  const storeName = localStorage.getItem('selectedStoreName')

  const [method,       setMethod]       = useState('CREDIT_CARD')
  const [paying,       setPaying]       = useState(false)
  const [error,        setError]        = useState('')
  const [savedCard,    setSavedCard]    = useState(null)
  const [useSaved,     setUseSaved]     = useState(false)
  const [saveCard,     setSaveCard]     = useState(false)
  const [cardNumber,   setCardNumber]   = useState('')
  const [expiry,       setExpiry]       = useState('')
  const [cvc,          setCvc]          = useState('')
  const [holderName,   setHolderName]   = useState('')

  useEffect(() => {
    const raw = localStorage.getItem(SAVED_KEY)
    if (raw) {
      const card = JSON.parse(raw)
      setSavedCard(card)
      setUseSaved(true)
      setMethod(card.method)
    }
  }, [])

  if (!state || !storeId) {
    navigate('/cart', { replace: true })
    return null
  }

  const { orderType, customerRequest, totalPrice, totalCount } = state

  const validate = () => {
    if (method === 'CREDIT_CARD' && !useSaved) {
      if (cardNumber.replace(/\D/g, '').length !== 16) return '카드 번호 16자리를 입력해주세요'
      if (expiry.length !== 5)                         return '유효기간을 입력해주세요'
      if (cvc.length < 3)                              return 'CVC 3자리를 입력해주세요'
      if (!holderName.trim())                          return '카드 소유자 이름을 입력해주세요'
    }
    return null
  }

  const handlePay = async () => {
    const validErr = validate()
    if (validErr) { setError(validErr); return }

    // 새 카드 + 저장 선택 시 로컬스토리지에 저장 (마지막 4자리만)
    if (method === 'CREDIT_CARD' && !useSaved && saveCard) {
      localStorage.setItem(SAVED_KEY, JSON.stringify({
        method,
        lastFour: cardNumber.replace(/\D/g, '').slice(-4),
        expiry,
        holderName: holderName.trim(),
      }))
    }

    setPaying(true)
    setError('')
    try {
      const res = await processPayment({
        method,
        storeId: Number(storeId),
        orderType,
        customerRequest: customerRequest || null,
      })
      navigate(`/orders/${res.data.data.orderId}`, { replace: true })
    } catch (err) {
      setError(err.response?.data?.message || '결제에 실패했습니다. 다시 시도해주세요.')
    } finally {
      setPaying(false)
    }
  }

  const removeSavedCard = () => {
    localStorage.removeItem(SAVED_KEY)
    setSavedCard(null)
    setUseSaved(false)
    setMethod('CREDIT_CARD')
  }

  const showCardForm = method === 'CREDIT_CARD' && !useSaved

  return (
    <CustomerLayout>
      <div className={styles.page}>
        <h1 className={styles.title}>결제하기</h1>

        {/* 주문 요약 */}
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

        {/* 저장된 결제 수단 */}
        {savedCard && (
          <section className={styles.card}>
            <h2 className={styles.sectionTitle}>저장된 결제 수단</h2>
            <label className={styles.radioRow}>
              <input type="radio" checked={useSaved} onChange={() => { setUseSaved(true); setMethod(savedCard.method) }} />
              <span className={styles.radioLabel}>
                💳 {savedCard.holderName}님의 카드&nbsp;
                <span className={styles.cardMasked}>**** **** **** {savedCard.lastFour}</span>
                &nbsp;({savedCard.expiry})
              </span>
              <button className={styles.removeBtn} onClick={removeSavedCard}>삭제</button>
            </label>
            <label className={styles.radioRow}>
              <input type="radio" checked={!useSaved} onChange={() => setUseSaved(false)} />
              <span className={styles.radioLabel}>새 결제 수단 사용</span>
            </label>
          </section>
        )}

        {/* 결제 수단 선택 */}
        {!useSaved && (
          <section className={styles.card}>
            <h2 className={styles.sectionTitle}>결제 수단</h2>
            <div className={styles.methodGrid}>
              {PAYMENT_METHODS.map(({ value, label, icon }) => (
                <button
                  key={value}
                  className={`${styles.methodBtn} ${method === value ? styles.methodBtnActive : ''}`}
                  onClick={() => setMethod(value)}
                >
                  <span className={styles.methodIcon}>{icon}</span>
                  <span className={styles.methodLabel}>{label}</span>
                </button>
              ))}
            </div>
          </section>
        )}

        {/* 카드 정보 입력 */}
        {showCardForm && (
          <section className={styles.card}>
            <h2 className={styles.sectionTitle}>카드 정보</h2>
            <div className={styles.cardForm}>
              <div className={styles.field}>
                <label className={styles.fieldLabel}>카드 번호</label>
                <input
                  className={styles.input}
                  placeholder="1234-5678-9012-3456"
                  value={cardNumber}
                  onChange={(e) => setCardNumber(fmtCard(e.target.value))}
                  maxLength={19}
                />
              </div>
              <div className={styles.fieldRow}>
                <div className={styles.field}>
                  <label className={styles.fieldLabel}>유효기간</label>
                  <input
                    className={styles.input}
                    placeholder="MM/YY"
                    value={expiry}
                    onChange={(e) => setExpiry(fmtExpiry(e.target.value))}
                    maxLength={5}
                  />
                </div>
                <div className={styles.field}>
                  <label className={styles.fieldLabel}>CVC</label>
                  <input
                    className={styles.input}
                    placeholder="123"
                    type="password"
                    value={cvc}
                    onChange={(e) => setCvc(e.target.value.replace(/\D/g, '').slice(0, 3))}
                    maxLength={3}
                  />
                </div>
              </div>
              <div className={styles.field}>
                <label className={styles.fieldLabel}>카드 소유자</label>
                <input
                  className={styles.input}
                  placeholder="이름을 입력하세요"
                  value={holderName}
                  onChange={(e) => setHolderName(e.target.value)}
                />
              </div>
              <label className={styles.checkRow}>
                <input type="checkbox" checked={saveCard} onChange={(e) => setSaveCard(e.target.checked)} />
                다음 결제할 때도 사용하기
              </label>
            </div>
          </section>
        )}

        {/* 간편결제 안내 */}
        {!useSaved && (method === 'KAKAO_PAY' || method === 'NAVER_PAY') && (
          <div className={styles.simplePayNotice}>
            {method === 'KAKAO_PAY' ? '🟡 카카오페이' : '🟢 네이버페이'}로 결제합니다.
          </div>
        )}

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
