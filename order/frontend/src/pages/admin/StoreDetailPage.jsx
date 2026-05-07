import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getStore, openStore, closeStore } from '../../api/store'
import AdminLayout from '../../components/admin/AdminLayout'
import styles from './StoreDetailPage.module.css'

export default function StoreDetailPage() {
  const { storeId } = useParams()
  const navigate = useNavigate()
  const [store, setStore] = useState(null)
  const [loading, setLoading] = useState(true)
  const [toggling, setToggling] = useState(false)
  const [error, setError] = useState('')

  const load = () => {
    setLoading(true)
    getStore(storeId)
      .then((res) => setStore(res.data.data))
      .catch(() => setError('매장 정보를 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [storeId])

  const handleToggle = async () => {
    setToggling(true)
    try {
      if (store.status === 'OPEN') {
        await closeStore(storeId)
      } else {
        await openStore(storeId)
      }
      load()
    } catch {
      alert('상태 변경 중 오류가 발생했습니다')
    } finally {
      setToggling(false)
    }
  }

  return (
    <AdminLayout>
      <div className={styles.header}>
        <button className={styles.backBtn} onClick={() => navigate('/admin/stores')}>
          ← 목록으로
        </button>
        <h1 className={styles.pageTitle}>{store?.name ?? '매장 상세'}</h1>

        {store && (
          <>
            <button
              className={styles.stockBtn}
              onClick={() => navigate(`/admin/stores/${storeId}/stocks`)}
            >
              재고 관리
            </button>
            <button
              className={`${styles.toggleBtn} ${store.status === 'OPEN' ? styles.closeBtn : styles.openBtn}`}
              onClick={handleToggle}
              disabled={toggling}
            >
              {toggling ? '변경 중...' : store.status === 'OPEN' ? '영업 종료' : '영업 시작'}
            </button>
          </>
        )}
      </div>

      {loading && <p className={styles.loading}>불러오는 중...</p>}
      {error   && <p className={styles.error}>{error}</p>}

      {store && (
        <div className={styles.card}>
          <p className={styles.sectionTitle}>기본 정보</p>
          <div className={styles.grid}>
            <Field label="매장명"   value={store.name} />
            <Field label="전화번호" value={store.phone || '-'} />
            <Field label="상태" value={
              <span className={`${styles.badge} ${store.status === 'OPEN' ? styles.badgeOpen : styles.badgeClosed}`}>
                {store.status === 'OPEN' ? '영업 중' : '마감'}
              </span>
            } />
            <Field label="점주명" value={store.ownerName} />
          </div>

          <p className={styles.sectionTitle}>주소 정보</p>
          <div className={styles.grid}>
            <Field label="우편번호"    value={store.postalCode} />
            <Field label="도로명 주소" value={store.address} />
            <Field label="상세 주소"   value={store.addressDetail || '-'} />
          </div>

          <p className={styles.sectionTitle}>영업 시간</p>
          <div className={styles.grid}>
            <Field label="영업 시작" value={store.openTime} />
            <Field label="영업 종료" value={store.closeTime} />
          </div>
        </div>
      )}
    </AdminLayout>
  )
}

function Field({ label, value }) {
  return (
    <div className={styles.item}>
      <span className={styles.label}>{label}</span>
      <span className={styles.value}>{value}</span>
    </div>
  )
}
