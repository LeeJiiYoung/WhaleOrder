import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { getOpenStores } from '../../api/store'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './StoreSelectPage.module.css'

export default function StoreSelectPage() {
  const navigate = useNavigate()
  const [stores, setStores] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    getOpenStores()
      .then((res) => setStores(res.data.data))
      .catch(() => setError('매장 목록을 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [])

  const handleSelect = (store) => {
    localStorage.setItem('selectedStoreId', store.storeId)
    localStorage.setItem('selectedStoreName', store.name)
    navigate('/menus')
  }

  return (
    <CustomerLayout>
      <div className={styles.header}>
        <h1 className={styles.title}>매장을 선택하세요</h1>
        <p className={styles.subtitle}>주문할 매장을 선택하면 메뉴를 확인할 수 있어요</p>
      </div>

      {loading && <div className={styles.empty}>불러오는 중...</div>}
      {error   && <div className={styles.errorBox}>{error}</div>}

      {!loading && !error && stores.length === 0 && (
        <div className={styles.empty}>현재 영업 중인 매장이 없습니다</div>
      )}

      <div className={styles.grid}>
        {stores.map((store) => (
          <button key={store.storeId} className={styles.card} onClick={() => handleSelect(store)}>
            <div className={styles.cardIcon}>🏪</div>
            <div className={styles.cardBody}>
              <p className={styles.storeName}>{store.name}</p>
              <p className={styles.storeAddress}>
                {store.address}
                {store.addressDetail ? ` ${store.addressDetail}` : ''}
              </p>
              <p className={styles.storeHours}>
                {store.openTime} ~ {store.closeTime}
              </p>
            </div>
            <span className={styles.openBadge}>영업 중</span>
          </button>
        ))}
      </div>
    </CustomerLayout>
  )
}
