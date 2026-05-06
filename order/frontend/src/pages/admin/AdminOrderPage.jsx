import { useState, useEffect, useCallback } from 'react'
import { getAdminOrders, changeOrderStatus } from '../../api/order'
import AdminLayout from '../../components/admin/AdminLayout'
import styles from './AdminOrderPage.module.css'

const STATUS_TABS = [
  { value: '',           label: '전체' },
  { value: 'PENDING',    label: '접수 대기' },
  { value: 'ACCEPTED',   label: '수락됨' },
  { value: 'PREPARING',  label: '제조 중' },
  { value: 'COMPLETED',  label: '완료' },
  { value: 'CANCELLED',  label: '취소됨' },
]

const STATUS_LABEL = {
  PENDING:   { text: '접수 대기', color: '#f59e0b', bg: '#fffbeb' },
  ACCEPTED:  { text: '수락됨',   color: '#3b82f6', bg: '#eff6ff' },
  PREPARING: { text: '제조 중',  color: '#8b5cf6', bg: '#f5f3ff' },
  COMPLETED: { text: '완료',     color: '#10b981', bg: '#ecfdf5' },
  CANCELLED: { text: '취소됨',   color: '#ef4444', bg: '#fef2f2' },
}

const NEXT_ACTIONS = {
  PENDING:   [{ action: 'accept',   label: '수락' }],
  ACCEPTED:  [{ action: 'prepare',  label: '제조 시작' }],
  PREPARING: [{ action: 'complete', label: '완료 처리' }],
  COMPLETED: [],
  CANCELLED: [],
}

const parseOptions = (raw) => {
  try { return JSON.parse(raw || '[]') } catch { return [] }
}

const ORDER_TYPE_LABEL = {
  TAKEOUT: '포장',
  DINE_IN: '매장 내 취식',
}

export default function AdminOrderPage() {
  const [orders, setOrders] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [activeTab, setActiveTab] = useState('')
  const [actionLoading, setActionLoading] = useState({})

  const load = useCallback(() => {
    setLoading(true)
    getAdminOrders(activeTab)
      .then((res) => setOrders(res.data.data))
      .catch(() => setError('주문 목록을 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [activeTab])

  useEffect(() => { load() }, [load])

  const handleAction = async (orderId, action) => {
    setActionLoading((prev) => ({ ...prev, [orderId]: true }))
    try {
      const res = await changeOrderStatus(orderId, action)
      setOrders((prev) => prev.map((o) => (o.orderId === orderId ? res.data.data : o)))
    } catch (err) {
      alert(err.response?.data?.message || '상태 변경에 실패했습니다')
    } finally {
      setActionLoading((prev) => ({ ...prev, [orderId]: false }))
    }
  }

  return (
    <AdminLayout>
      <div className={styles.header}>
        <h1 className={styles.title}>주문 현황</h1>
        <button className={styles.refreshBtn} onClick={load}>새로고침</button>
      </div>

      {/* 상태 탭 */}
      <div className={styles.tabs}>
        {STATUS_TABS.map(({ value, label }) => (
          <button
            key={value}
            className={`${styles.tab} ${activeTab === value ? styles.tabActive : ''}`}
            onClick={() => setActiveTab(value)}
          >
            {label}
          </button>
        ))}
      </div>

      {loading && <div className={styles.empty}>불러오는 중...</div>}
      {error   && <div className={styles.errorBox}>{error}</div>}

      {!loading && !error && orders.length === 0 && (
        <div className={styles.empty}>해당 상태의 주문이 없습니다</div>
      )}

      <div className={styles.list}>
        {orders.map((order) => {
          const statusInfo = STATUS_LABEL[order.status] || { text: order.status, color: '#888', bg: '#f5f5f5' }
          const actions = NEXT_ACTIONS[order.status] ?? []
          const isLoading = !!actionLoading[order.orderId]

          return (
            <div key={order.orderId} className={styles.card}>
              <div className={styles.cardTop}>
                <div className={styles.cardLeft}>
                  <span className={styles.orderNum}>주문 #{order.orderId}</span>
                  <span className={styles.memberName}>{order.memberNickname}</span>
                </div>
                <span
                  className={styles.statusBadge}
                  style={{ color: statusInfo.color, background: statusInfo.bg }}
                >
                  {statusInfo.text}
                </span>
              </div>

              <div className={styles.meta}>
                <span>{order.storeName}</span>
                <span className={styles.dot}>·</span>
                <span>{ORDER_TYPE_LABEL[order.orderType] ?? order.orderType}</span>
                <span className={styles.dot}>·</span>
                <span>{new Date(order.orderedAt).toLocaleString('ko-KR', { hour: '2-digit', minute: '2-digit' })}</span>
              </div>

              <div className={styles.items}>
                {order.items.map((item, idx) => (
                  <div key={idx} className={styles.itemRow}>
                    <span className={styles.itemName}>
                      {item.menuName}
                      {parseOptions(item.options).length > 0 && (
                        <span className={styles.itemOptions}> ({parseOptions(item.options).map((o) => o.optionName).join(', ')})</span>
                      )}
                    </span>
                    <span className={styles.itemQty}>x{item.quantity}</span>
                  </div>
                ))}
              </div>

              {order.customerRequest && (
                <p className={styles.request}>요청: {order.customerRequest}</p>
              )}

              <div className={styles.cardBottom}>
                <span className={styles.totalPrice}>{order.totalPrice.toLocaleString()}원</span>
                <div className={styles.actionBtns}>
                  {actions.map(({ action, label }) => (
                    <button
                      key={action}
                      className={styles.actionBtn}
                      onClick={() => handleAction(order.orderId, action)}
                      disabled={isLoading}
                    >
                      {isLoading ? '처리 중...' : label}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )
        })}
      </div>
    </AdminLayout>
  )
}
