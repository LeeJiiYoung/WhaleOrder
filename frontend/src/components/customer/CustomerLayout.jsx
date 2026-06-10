import { useNavigate, useLocation } from 'react-router-dom'
import { useEffect, useState } from 'react'
import { getCart } from '../../api/cart'
import styles from './CustomerLayout.module.css'

/**
 * 고객 전용 레이아웃 컴포넌트.
 *
 * - 헤더: 브랜드명(클릭 시 /stores), 선택된 매장명, 장바구니 아이콘(수량 뱃지), 로그아웃
 * - 경로가 바뀔 때마다 장바구니 수량을 다시 조회해 뱃지를 최신 상태로 유지
 * - 선택된 매장명은 localStorage의 selectedStoreName에서 읽음
 * @param {{ children: React.ReactNode }} props
 */
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
    localStorage.clear()
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
          <button className={styles.nickname} onClick={() => navigate('/profile')}>{nickname}님</button>
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
