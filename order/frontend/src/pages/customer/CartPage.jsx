import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { getCart, updateQuantity, removeFromCart, clearCart } from '../../api/cart'
import { createOrder, subscribeOrderResult } from '../../api/order'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './CartPage.module.css'

const ORDER_TYPES = [
  { value: 'TAKEOUT', label: '포장' },
  { value: 'DINE_IN', label: '매장 내 취식' },
]

export default function CartPage() {
  const navigate = useNavigate()
  const [cart, setCart] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [orderType, setOrderType] = useState('TAKEOUT')
  const [customerRequest, setCustomerRequest] = useState('')
  const [ordering, setOrdering] = useState(false)
  const [queueState, setQueueState] = useState(null) // { orderId, position }
  const cancelSseRef = useRef(null)

  // 언마운트 시 SSE 연결 해제
  useEffect(() => () => cancelSseRef.current?.(), [])

  const load = () => {
    setLoading(true)
    getCart()
      .then((res) => setCart(res.data.data))
      .catch(() => setError('장바구니를 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const handleQuantity = async (itemKey, quantity) => {
    try {
      const res = await updateQuantity(itemKey, quantity)
      setCart(res.data.data)
    } catch {
      alert('수량 변경에 실패했습니다')
    }
  }

  const handleRemove = async (itemKey) => {
    try {
      const res = await removeFromCart(itemKey)
      setCart(res.data.data)
    } catch {
      alert('삭제에 실패했습니다')
    }
  }

  const handleClear = async () => {
    if (!window.confirm('장바구니를 비울까요?')) return
    try {
      await clearCart()
      load()
    } catch {
      alert('비우기에 실패했습니다')
    }
  }

  const handleOrder = async () => {
    const storeId = localStorage.getItem('selectedStoreId')
    if (!storeId) {
      alert('매장을 먼저 선택해주세요')
      navigate('/stores')
      return
    }
    setOrdering(true)
    try {
      const idempotencyKey = crypto.randomUUID()
      const res = await createOrder(
        { storeId: Number(storeId), orderType, customerRequest: customerRequest.trim() || null },
        idempotencyKey,
      )
      const { orderId, position } = res.data.data
      setQueueState({ orderId, position })

      cancelSseRef.current = subscribeOrderResult(orderId, (data) => {
        cancelSseRef.current = null
        setQueueState(null)
        setOrdering(false)
        if (data.status === 'SUCCESS') {
          navigate(`/orders/${orderId}`)
        } else {
          alert('주문 처리에 실패했습니다. 재고가 부족할 수 있습니다.')
        }
      })
    } catch (err) {
      alert(err.response?.data?.message || '주문에 실패했습니다')
      setOrdering(false)
    }
  }

  const isEmpty = !cart || cart.items.length === 0

  return (
    <CustomerLayout>
      {/* 대기열 처리 오버레이 */}
      {queueState && (
        <div className={styles.queueOverlay}>
          <div className={styles.queueBox}>
            <div className={styles.queueSpinner} />
            <p className={styles.queueTitle}>주문 처리 중</p>
            <p className={styles.queuePos}>{queueState.position}번째 대기 중</p>
            <p className={styles.queueHint}>처리 완료 시 자동으로 이동합니다</p>
          </div>
        </div>
      )}

      <div className={styles.titleRow}>
        <h1 className={styles.title}>장바구니</h1>
        {!isEmpty && (
          <button className={styles.clearBtn} onClick={handleClear}>전체 비우기</button>
        )}
      </div>

      {loading && <div className={styles.empty}>불러오는 중...</div>}
      {error   && <div className={styles.errorBox}>{error}</div>}

      {!loading && isEmpty && (
        <div className={styles.emptyState}>
          <p className={styles.emptyIcon}>🛒</p>
          <p className={styles.emptyText}>장바구니가 비어 있어요</p>
          <button className={styles.goMenuBtn} onClick={() => navigate('/menus')}>
            메뉴 보러 가기
          </button>
        </div>
      )}

      {!loading && !isEmpty && (
        <div className={styles.layout}>
          <div className={styles.itemList}>
            {cart.items.map((item) => (
              <CartItemRow
                key={item.itemKey}
                item={item}
                onQuantity={handleQuantity}
                onRemove={handleRemove}
              />
            ))}
          </div>

          <div className={styles.summary}>
            <p className={styles.summaryTitle}>주문 요약</p>

            {/* 주문 유형 */}
            <div className={styles.orderTypeRow}>
              {ORDER_TYPES.map(({ value, label }) => (
                <button
                  key={value}
                  className={`${styles.typeBtn} ${orderType === value ? styles.typeBtnActive : ''}`}
                  onClick={() => setOrderType(value)}
                >
                  {label}
                </button>
              ))}
            </div>

            {/* 요청사항 */}
            <textarea
              className={styles.requestInput}
              placeholder="요청사항 (선택)"
              value={customerRequest}
              onChange={(e) => setCustomerRequest(e.target.value)}
              rows={2}
            />

            <div className={styles.summaryRow}>
              <span>상품 수</span>
              <span>{cart.totalCount}개</span>
            </div>
            <div className={`${styles.summaryRow} ${styles.totalRow}`}>
              <span>합계</span>
              <span>{cart.totalPrice.toLocaleString()}원</span>
            </div>
            <button className={styles.orderBtn} onClick={handleOrder} disabled={ordering}>
              {ordering ? '대기열 등록 중...' : `${cart.totalPrice.toLocaleString()}원 주문하기`}
            </button>
          </div>
        </div>
      )}
    </CustomerLayout>
  )
}

function CartItemRow({ item, onQuantity, onRemove }) {
  const [updating, setUpdating] = useState(false)

  const change = async (qty) => {
    setUpdating(true)
    await onQuantity(item.itemKey, qty)
    setUpdating(false)
  }

  return (
    <div className={styles.item}>
      <div className={styles.itemImage}>
        {item.imageUrl
          ? <img src={item.imageUrl} alt={item.menuName} />
          : <div className={styles.itemImagePlaceholder}>☕</div>
        }
      </div>

      <div className={styles.itemBody}>
        <p className={styles.itemName}>{item.menuName}</p>
        {item.selectedOptions?.length > 0 && (
          <p className={styles.itemOptions}>
            {item.selectedOptions.map((o) => o.optionName).join(' · ')}
          </p>
        )}
        <p className={styles.itemUnitPrice}>{item.unitPrice.toLocaleString()}원</p>
      </div>

      <div className={styles.itemRight}>
        <div className={styles.quantityControl}>
          <button className={styles.qBtn} disabled={updating} onClick={() => change(item.quantity - 1)}>−</button>
          <span className={styles.qValue}>{item.quantity}</span>
          <button className={styles.qBtn} disabled={updating} onClick={() => change(item.quantity + 1)}>+</button>
        </div>
        <p className={styles.itemTotal}>{item.totalPrice.toLocaleString()}원</p>
        <button className={styles.removeBtn} onClick={() => onRemove(item.itemKey)}>삭제</button>
      </div>
    </div>
  )
}
