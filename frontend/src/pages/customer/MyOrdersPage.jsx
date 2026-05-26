import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { getMyOrders } from '../../api/order'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './MyOrdersPage.module.css'

const STATUS_LABEL = {
  PENDING:   { text: '접수 대기', color: '#f59e0b' },
  ACCEPTED:  { text: '주문 수락', color: '#3b82f6' },
  PREPARING: { text: '제조 중',   color: '#8b5cf6' },
  COMPLETED: { text: '완료',      color: '#10b981' },
  CANCELLED: { text: '취소됨',    color: '#ef4444' },
}

const ORDER_TYPE_LABEL = {
  TAKEOUT: '포장',
  DINE_IN: '매장 내 취식',
}

export default function MyOrdersPage() {
  const navigate = useNavigate()
  const [orders, setOrders] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    getMyOrders()
      .then((res) => setOrders(res.data.data))
      .catch(() => setError('주문 내역을 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [])

  return (
    <CustomerLayout>
      <h1 className={styles.title}>주문 내역</h1>

      {loading && <div className={styles.empty}>불러오는 중...</div>}
      {error   && <div className={styles.errorBox}>{error}</div>}

      {!loading && !error && orders.length === 0 && (
        <div className={styles.emptyState}>
          <p className={styles.emptyIcon}>📋</p>
          <p className={styles.emptyText}>주문 내역이 없어요</p>
          <button className={styles.goMenuBtn} onClick={() => navigate('/menus')}>
            메뉴 보러 가기
          </button>
        </div>
      )}

      <div className={styles.list}>
        {orders.map((order) => {
          const statusInfo = STATUS_LABEL[order.status] || { text: order.status, color: '#888' }
          return (
            <button
              key={order.orderId}
              className={styles.card}
              onClick={() => navigate(`/orders/${order.orderId}`)}
            >
              <div className={styles.cardTop}>
                <span className={styles.orderNum}>주문 #{order.orderId}</span>
                <span className={styles.statusBadge} style={{ color: statusInfo.color, borderColor: statusInfo.color }}>
                  {statusInfo.text}
                </span>
              </div>
              <p className={styles.storeName}>{order.storeName}</p>
              <p className={styles.meta}>
                {ORDER_TYPE_LABEL[order.orderType] ?? order.orderType}
                {' · '}
                {new Date(order.orderedAt).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
              </p>
              <p className={styles.summary}>
                {order.items.map((i) => i.menuName).join(', ')} 외 {order.items.length - 1 > 0 ? `${order.items.length - 1}건` : ''}
              </p>
              <p className={styles.totalPrice}>{order.totalPrice.toLocaleString()}원</p>
            </button>
          )
        })}
      </div>
    </CustomerLayout>
  )
}
