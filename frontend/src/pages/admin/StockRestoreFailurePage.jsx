import { useState, useEffect } from 'react'
import { getStockRestoreFailures } from '../../api/stock'
import AdminLayout from '../../components/admin/AdminLayout'
import styles from './StockRestoreFailurePage.module.css'

/**
 * 관리자 재고 복구 실패 목록 페이지. (@route /admin/stock-restore-failures)
 *
 * - 주문 취소 시 재고 복구에 실패한 내역을 조회
 * - 발생 시각·주문 ID·매장 ID·메뉴 ID·수량·실패 원인 표시
 * - SSE 알림을 놓쳤을 때 이 페이지에서 확인 후 수동 보정
 */
export default function StockRestoreFailurePage() {
  const [failures, setFailures] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    getStockRestoreFailures()
      .then((res) => setFailures(res.data.data))
      .catch(() => setError('목록을 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [])

  const fmt = (iso) =>
    new Date(iso).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' })

  return (
    <AdminLayout>
      <div className={styles.header}>
        <h1 className={styles.title}>재고 복구 실패 목록</h1>
        <p className={styles.desc}>SSE 알림을 놓쳤을 때 여기서 확인 후 수동 보정하세요.</p>
      </div>

      {loading && <div className={styles.empty}>불러오는 중...</div>}
      {error   && <div className={styles.errorBox}>{error}</div>}

      {!loading && !error && failures.length === 0 && (
        <div className={styles.empty}>재고 복구 실패 내역이 없습니다.</div>
      )}

      {!loading && !error && failures.length > 0 && (
        <div className={styles.table}>
          <div className={styles.tableHead}>
            <span>발생 시각</span>
            <span>주문 ID</span>
            <span>매장 ID</span>
            <span>메뉴 ID</span>
            <span>수량</span>
            <span>실패 원인</span>
          </div>
          {failures.map((f) => (
            <div key={f.id} className={styles.tableRow}>
              <span className={styles.time}>{fmt(f.failedAt)}</span>
              <span>{f.orderId}</span>
              <span>{f.storeId}</span>
              <span>{f.menuId}</span>
              <span className={styles.qty}>{f.quantity}개</span>
              <span className={styles.reason}>{f.reason}</span>
            </div>
          ))}
        </div>
      )}
    </AdminLayout>
  )
}
