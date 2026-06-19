import { useState, useEffect, useMemo } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { getCustomerMenus } from '../../api/menu'
import CustomerLayout from '../../components/customer/CustomerLayout'
import CategoryTabs from '../../components/customer/CategoryTabs'
import styles from './CustomerMenuListPage.module.css'

/**
 * 고객 메뉴 목록 페이지. (@route /menus)
 *
 * - 카테고리 탭 필터(전체·음료·푸드·디저트·드링크) + 메뉴명 키워드 검색
 * - 카테고리는 URL 쿼리(`?category=`) 로 동기화되며, /events 페이지에서 탭 클릭 시에도 일관되게 동작
 * - 카테고리 변경 시 서버 재요청, 키워드 검색은 클라이언트 필터링
 * - 메뉴 카드 클릭 시 /menus/:menuId 상세 페이지로 이동
 */
export default function CustomerMenuListPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const category = searchParams.get('category') || ''
  const storeId = localStorage.getItem('selectedStoreId')
  const storeName = localStorage.getItem('selectedStoreName')
  const [menus, setMenus] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [keyword, setKeyword] = useState('')

  useEffect(() => {
    if (!storeId) {
      navigate('/stores', { replace: true })
      return
    }
    setLoading(true)
    getCustomerMenus(storeId, category || undefined)
      .then((res) => setMenus(res.data.data))
      .catch(() => setError('메뉴를 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [storeId, category])

  // 카테고리 바뀌면 키워드 초기화
  useEffect(() => { setKeyword('') }, [category])

  const filtered = useMemo(() => {
    if (!keyword.trim()) return menus
    return menus.filter((m) => m.name.includes(keyword.trim()))
  }, [menus, keyword])

  return (
    <CustomerLayout>
      {storeName && <p className={styles.storeName}>📍 {storeName}</p>}
      <div className={styles.filterBar}>
        <CategoryTabs current={category} />
        <input
          className={styles.searchInput}
          placeholder="메뉴 검색"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />
      </div>

      {error && <div className={styles.errorBox}>{error}</div>}

      {loading ? (
        <div className={styles.empty}>불러오는 중...</div>
      ) : filtered.length === 0 ? (
        <div className={styles.empty}>메뉴가 없습니다</div>
      ) : (
        <div className={styles.grid}>
          {filtered.map((menu) => (
            <button
              key={menu.menuId}
              className={`${styles.card} ${menu.soldOut ? styles.soldOut : ''}`}
              disabled={menu.soldOut}
              onClick={() => navigate(`/menus/${menu.menuId}`, { state: { menu } })}
            >
              <div className={styles.imageWrap}>
                {menu.imageUrl ? (
                  <img src={menu.imageUrl} alt={menu.name} className={styles.image} />
                ) : (
                  <div className={styles.imagePlaceholder}>☕</div>
                )}
                {menu.soldOut && <div className={styles.soldOutBadge}>품절</div>}
              </div>
              <div className={styles.cardBody}>
                <p className={styles.menuName}>{menu.name}</p>
                {menu.description && (
                  <p className={styles.menuDesc}>{menu.description}</p>
                )}
                <p className={styles.menuPrice}>{menu.basePrice.toLocaleString()}원</p>
              </div>
            </button>
          ))}
        </div>
      )}
    </CustomerLayout>
  )
}
