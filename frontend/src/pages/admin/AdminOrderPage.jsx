import { useState, useEffect, useCallback, useRef } from 'react'
import { getAdminOrders, changeOrderStatus } from '../../api/order'
import AdminLayout from '../../components/admin/AdminLayout'
import styles from './AdminOrderPage.module.css'

const STATUS_TABS = [
  { value: 'ACTIVE',    label: '진행 중', statuses: ['PENDING', 'ACCEPTED', 'PREPARING'] },
  { value: '',          label: '전체',    statuses: [] },
  { value: 'PENDING',   label: '접수 대기', statuses: ['PENDING'] },
  { value: 'ACCEPTED',  label: '수락됨',   statuses: ['ACCEPTED'] },
  { value: 'PREPARING', label: '제조 중',  statuses: ['PREPARING'] },
  { value: 'COMPLETED', label: '완료',     statuses: ['COMPLETED'] },
  { value: 'CANCELLED', label: '취소됨',   statuses: ['CANCELLED'] },
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
  const [orders, setOrders]           = useState([])
  const [loading, setLoading]         = useState(true)
  const [error, setError]             = useState('')
  const [activeTab, setActiveTab]     = useState('ACTIVE')
  const [actionLoading, setActionLoading] = useState({})
  const [toasts, setToasts]           = useState([])

  // SSE 재연결 시 activeTab 최신값을 읽기 위한 ref
  const activeTabRef = useRef(activeTab)
  useEffect(() => { activeTabRef.current = activeTab }, [activeTab])

  // ── 토스트 ────────────────────────────────────────────────────────
  const addToast = useCallback((msg) => {
    const id = Date.now()
    setToasts((prev) => [...prev, { id, msg }])
    setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), 4000)
  }, [])

  // ── 주문 목록 로드 ────────────────────────────────────────────────
  const load = useCallback(() => {
    setLoading(true)
    const tab = STATUS_TABS.find((t) => t.value === activeTab)
    getAdminOrders(tab?.statuses ?? [])
      .then((res) => setOrders(res.data.data))
      .catch(() => setError('주문 목록을 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [activeTab])

  useEffect(() => { load() }, [load])

  // ── SSE 연결 (새 주문 실시간 알림) ───────────────────────────────
  useEffect(() => {
    const controller = new AbortController()

    const connect = async () => {
      const token = localStorage.getItem('accessToken')
      try {
        const res = await fetch('/api/admin/orders/stream', {
          headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
          signal: controller.signal,
        })
        if (!res.ok || !res.body) return

        const reader  = res.body.getReader()
        const decoder = new TextDecoder()
        let buf = ''

        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          buf += decoder.decode(value, { stream: true })

          const parts = buf.split('\n\n')
          buf = parts.pop()

          for (const part of parts) {
            let eventName = '', data = ''
            for (const line of part.trim().split('\n')) {
              if (line.startsWith('event:')) eventName = line.slice(6).trim()
              if (line.startsWith('data:'))  data      = line.slice(5).trim()
            }

            if (eventName === 'newOrder' && data) {
              const order = JSON.parse(data)
              addToast(`🔔 새 주문 #${order.orderId} — ${order.memberNickname}`)

              // 진행 중 / 접수 대기 탭이면 목록 상단에 즉시 추가
              const currentTab = STATUS_TABS.find((t) => t.value === activeTabRef.current)
              const showNow = !currentTab?.statuses.length /* 전체 */ ||
                              currentTab.statuses.includes('PENDING')
              if (showNow) {
                setOrders((prev) => [order, ...prev.filter((o) => o.orderId !== order.orderId)])
              }
            }
            // heartbeat 이벤트는 무시
          }
        }
      } catch (err) {
        if (err.name === 'AbortError') return
        // 네트워크 오류 시 3초 후 재연결
        setTimeout(connect, 3000)
      }
    }

    connect()
    return () => controller.abort()
  }, [addToast])

  // ── 주문 상태 변경 ────────────────────────────────────────────────
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
      {/* 토스트 알림 */}
      <div className={styles.toastContainer}>
        {toasts.map(({ id, msg }) => (
          <div key={id} className={styles.toast}>{msg}</div>
        ))}
      </div>

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
          const actions    = NEXT_ACTIONS[order.status] ?? []
          const isLoading  = !!actionLoading[order.orderId]

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
