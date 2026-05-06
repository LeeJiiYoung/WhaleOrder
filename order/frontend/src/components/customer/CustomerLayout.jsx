import { useNavigate, useLocation } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { getCart } from '../../api/cart'
import styles from './CustomerLayout.module.css'

export default function CustomerLayout({ children }) {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const nickname = localStorage.getItem('nickname') || '회원'
  const storeName = localStorage.getItem('selectedStoreName')
  const [cartCount, setCartCount] = useState(0)

  useEffect(() => {
    getCart()
      .then((res) => setCartCount(res.data.data.totalCount))
      .catch(() => {})
  }, [pathname])

  const handleLogout = () => {
    localStorage.removeItem('accessToken')
    localStorage.removeItem('nickname')
    localStorage.removeItem('role')
    localStorage.removeItem('selectedStoreId')
    localStorage.removeItem('selectedStoreName')
    navigate('/login')
  }

  return (
    <div className={styles.root}>
      <header className={styles.header}>
        <span className={styles.brand} onClick={() => navigate('/stores')}>
          🐋 WhaleOrder
        </span>

        <button
          className={styles.storeSelector}
          onClick={() => navigate('/stores')}
          title="매장 변경"
        >
          📍 {storeName ?? '매장을 선택하세요'}
        </button>

        <div className={styles.right}>
          <span className={styles.nickname}>{nickname}님</span>
          <button className={styles.ordersBtn} onClick={() => navigate('/my-orders')}>주문 내역</button>
          <button className={styles.cartBtn} onClick={() => navigate('/cart')}>
            🛒
            {cartCount > 0 && <span className={styles.cartBadge}>{cartCount}</span>}
          </button>
          <button className={styles.logoutBtn} onClick={handleLogout}>로그아웃</button>
        </div>
      </header>

      <main className={styles.content}>{children}</main>
    </div>
  )
}
