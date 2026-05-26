import { useState, useEffect, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { getStores } from '../../api/store'
import AdminLayout from '../../components/admin/AdminLayout'
import styles from './StoreListPage.module.css'

export default function StoreListPage() {
  const navigate = useNavigate()
  const [stores, setStores] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')

  useEffect(() => {
    getStores()
      .then((res) => setStores(res.data.data))
      .catch(() => setError('매장 목록을 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [])

  const filtered = useMemo(() => {
    return stores.filter((s) => {
      const matchKeyword = keyword === '' || s.name.includes(keyword) || s.ownerName.includes(keyword)
      const matchStatus  = statusFilter === 'ALL' || s.status === statusFilter
      return matchKeyword && matchStatus
    })
  }, [stores, keyword, statusFilter])

  return (
    <AdminLayout>
      <h1 className={styles.pageTitle}>매장 목록</h1>

      {/* 검색/필터 영역 — 항상 표시 */}
      <div className={styles.filterBar}>
        <input
          className={styles.searchInput}
          placeholder="매장명 또는 점주명 검색"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />
        <div className={styles.statusTabs}>
          {[
            { value: 'ALL',    label: '전체' },
            { value: 'OPEN',   label: '영업 중' },
            { value: 'CLOSED', label: '마감' },
          ].map(({ value, label }) => (
            <button
              key={value}
              className={`${styles.tab} ${statusFilter === value ? styles.tabActive : ''}`}
              onClick={() => setStatusFilter(value)}
            >
              {label}
            </button>
          ))}
        </div>
        <span className={styles.count}>
          {loading ? '-' : `${filtered.length}개`}
        </span>
      </div>

      {/* 테이블 */}
      <table className={styles.table}>
        <thead>
          <tr>
            <th>매장명</th>
            <th>전화번호</th>
            <th>영업 시작</th>
            <th>영업 종료</th>
            <th>점주명</th>
            <th>상태</th>
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <tr><td colSpan={6} className={styles.empty}>불러오는 중...</td></tr>
          ) : error ? (
            <tr><td colSpan={6} className={styles.empty}>{error}</td></tr>
          ) : filtered.length === 0 ? (
            <tr><td colSpan={6} className={styles.empty}>매장이 없습니다</td></tr>
          ) : (
            filtered.map((s) => (
              <tr key={s.storeId}>
                <td>
                  <span className={styles.nameLink} onClick={() => navigate(`/admin/stores/${s.storeId}`)}>
                    {s.name}
                  </span>
                </td>
                <td>{s.phone || '-'}</td>
                <td>{s.openTime}</td>
                <td>{s.closeTime}</td>
                <td>{s.ownerName}</td>
                <td>
                  <span className={`${styles.badge} ${s.status === 'OPEN' ? styles.badgeOpen : styles.badgeClosed}`}>
                    {s.status === 'OPEN' ? '영업 중' : '마감'}
                  </span>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </AdminLayout>
  )
}
