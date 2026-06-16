import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { getMyStores, openStore, closeStore } from '../../api/store'
import AdminLayout from '../../components/admin/AdminLayout'
import Breadcrumb from '../../components/admin/Breadcrumb'
import styles from './StoreListPage.module.css'

/**
 * 점주(OWNER) 본인 매장 목록 페이지. (@route /admin/my-stores)
 *
 * - 본인 소유 매장만 표시 (매장 생성·정보 수정은 ADMIN 전용이라 제공하지 않음)
 * - 영업 시작/종료는 매장별로 점주가 직접 변경 가능
 * - "재고 관리" 클릭 시 /admin/stores/:storeId/stocks 로 이동
 */
export default function MyStoresPage() {
  const navigate = useNavigate()
  const [stores, setStores] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [togglingId, setTogglingId] = useState(null)

  const load = () => {
    setLoading(true)
    getMyStores()
      .then((res) => setStores(res.data.data))
      .catch(() => setError('매장 목록을 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const handleToggle = async (store) => {
    setTogglingId(store.storeId)
    try {
      store.status === 'OPEN' ? await closeStore(store.storeId) : await openStore(store.storeId)
      load()
    } catch {
      alert('상태 변경 중 오류가 발생했습니다')
    } finally {
      setTogglingId(null)
    }
  }

  return (
    <AdminLayout>
      <Breadcrumb items={[{ label: '매장 관리' }, { label: '내 매장' }]} />
      <h1 className={styles.pageTitle}>내 매장</h1>

      <table className={styles.table}>
        <thead>
          <tr>
            <th>매장명</th>
            <th>전화번호</th>
            <th>영업 시작</th>
            <th>영업 종료</th>
            <th>상태</th>
            <th></th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <tr><td colSpan={7} className={styles.empty}>불러오는 중...</td></tr>
          ) : error ? (
            <tr><td colSpan={7} className={styles.empty}>{error}</td></tr>
          ) : stores.length === 0 ? (
            <tr><td colSpan={7} className={styles.empty}>소유한 매장이 없습니다</td></tr>
          ) : (
            stores.map((s) => (
              <tr key={s.storeId}>
                <td>{s.name}</td>
                <td>{s.phone || '-'}</td>
                <td>{s.openTime}</td>
                <td>{s.closeTime}</td>
                <td>
                  <span className={`${styles.badge} ${s.status === 'OPEN' ? styles.badgeOpen : styles.badgeClosed}`}>
                    {s.status === 'OPEN' ? '영업 중' : '마감'}
                  </span>
                </td>
                <td>
                  <button
                    className={styles.tab}
                    onClick={() => handleToggle(s)}
                    disabled={togglingId === s.storeId}
                  >
                    {togglingId === s.storeId ? '변경 중...' : s.status === 'OPEN' ? '영업 종료' : '영업 시작'}
                  </button>
                </td>
                <td>
                  <button
                    className={styles.tab}
                    onClick={() => navigate(`/admin/stores/${s.storeId}/stocks`)}
                  >
                    재고 관리
                  </button>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </AdminLayout>
  )
}
