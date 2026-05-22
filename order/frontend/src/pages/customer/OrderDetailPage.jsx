import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getOrder, cancelOrder } from '../../api/order'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './OrderDetailPage.module.css'

const parseOptions = (raw) => {
  try { return JSON.parse(raw || '[]') } catch { return [] }
}

const STATUS_LABEL = {
  PENDING:    { text: '접수 대기', color: '#f59e0b' },
  ACCEPTED:   { text: '주문 수락', color: '#3b82f6' },
  PREPARING:  { text: '제조 중',   color: '#8b5cf6' },
  COMPLETED:  { text: '완료',      color: '#10b981' },
  CANCELLED:  { text: '취소됨',    color: '#ef4444' },
}

const ORDER_TYPE_LABEL = {
  TAKEOUT: '포장',
  DINE_IN: '매장 내 취식',
}

export default function OrderDetailPage() {
  const { orderId } = useParams()
  const navigate = useNavigate()
  const [order, setOrder] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [cancelling, setCancelling] = useState(false)

  useEffect(() => {
    getOrder(orderId)
      .then((res) => setOrder(res.data.data))
      .catch(() => setError('주문 정보를 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [orderId])

  const handleCancel = async () => {
    if (!window.confirm('주문을 취소하시겠습니까?')) return
    setCancelling(true)
    try {
      const res = await cancelOrder(orderId)
      setOrder(res.data.data)
    } catch (err) {
      alert(err.response?.data?.message || '취소에 실패했습니다')
    } finally {
      setCancelling(false)
    }
  }

  if (loading) return <CustomerLayout><div className={styles.center}>불러오는 중...</div></CustomerLayout>
  if (error)   return <CustomerLayout><div className={styles.centerError}>{error}</div></CustomerLayout>
  if (!order)  return null

  const statusInfo = STATUS_LABEL[order.status] || { text: order.status, color: '#888' }
  const canCancel = order.status === 'PENDING'

  return (
    <CustomerLayout>
      <div className={styles.page}>
        {/* 상태 배너 */}
        <div className={styles.statusBanner} style={{ borderColor: statusInfo.color }}>
          <p className={styles.statusLabel} style={{ color: statusInfo.color }}>{statusInfo.text}</p>
          <p className={styles.orderNum}>주문번호 #{order.orderId}</p>
        </div>

        {/* 주문 정보 */}
        <div className={styles.card}>
          <p className={styles.cardTitle}>주문 정보</p>
          <div className={styles.infoRow}>
            <span className={styles.infoKey}>매장</span>
            <span className={styles.infoVal}>{order.storeName}</span>
          </div>
          <div className={styles.infoRow}>
            <span className={styles.infoKey}>주문 방식</span>
            <span className={styles.infoVal}>{ORDER_TYPE_LABEL[order.orderType] ?? order.orderType}</span>
          </div>
          {order.customerRequest && (
            <div className={styles.infoRow}>
              <span className={styles.infoKey}>요청사항</span>
              <span className={styles.infoVal}>{order.customerRequest}</span>
            </div>
          )}
          <div className={styles.infoRow}>
            <span className={styles.infoKey}>주문 시각</span>
            <span className={styles.infoVal}>{new Date(order.orderedAt).toLocaleString('ko-KR')}</span>
          </div>
        </div>

        {/* 메뉴 목록 */}
        <div className={styles.card}>
          <p className={styles.cardTitle}>주문 메뉴</p>
          {order.items.map((item, idx) => {
            const opts = parseOptions(item.options)
            return (
              <div key={idx} className={styles.itemRow}>
                <div className={styles.itemInfo}>
                  <span className={styles.itemName}>{item.menuName}</span>
                  {opts.length > 0 && (
                    <span className={styles.itemOptions}>
                      {opts.map((o) => o.optionName).join(' · ')}
                    </span>
                  )}
                </div>
                <div className={styles.itemRight}>
                  <span className={styles.itemQty}>x{item.quantity}</span>
                  <span className={styles.itemPrice}>{item.subTotal.toLocaleString()}원</span>
                </div>
              </div>
            )
          })}
          <div className={styles.totalRow}>
            <span>합계</span>
            <span className={styles.totalPrice}>{order.totalPrice.toLocaleString()}원</span>
          </div>
        </div>

        {/* 버튼 영역 */}
        <div className={styles.actions}>
          {canCancel && (
            <button className={styles.cancelBtn} onClick={handleCancel} disabled={cancelling}>
              {cancelling ? '취소 중...' : '주문 취소'}
            </button>
          )}
          <button className={styles.historyBtn} onClick={() => navigate('/my-orders')}>
            주문 내역 보기
          </button>
          <button className={styles.menuBtn} onClick={() => navigate('/menus')}>
            계속 주문하기
          </button>
        </div>
      </div>
    </CustomerLayout>
  )
}
