import { useState, useEffect, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { getCustomerMenus } from '../../api/menu'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './CustomerMenuListPage.module.css'

const CATEGORIES = [
  { value: '',         label: '전체' },
  { value: 'BEVERAGE', label: '음료' },
  { value: 'FOOD',     label: '푸드' },
  { value: 'DESSERT',  label: '디저트' },
  { value: 'DRINK',    label: '드링크' },
]

/**
 * 고객 메뉴 목록 페이지. (@route /menus)
 *
 * - 카테고리 탭 필터(전체·음료·푸드·디저트·드링크) + 메뉴명 키워드 검색
 * - 카테고리 변경 시 서버 재요청, 키워드 검색은 클라이언트 필터링
 * - "🎁 한정판매" 탭 버튼으로 /events로 이동
 * - 메뉴 카드 클릭 시 /menus/:menuId 상세 페이지로 이동
 */
export default function CustomerMenuListPage() {
  const navigate = useNavigate()
  const [menus, setMenus] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [category, setCategory] = useState('')
  const [keyword, setKeyword] = useState('')

  useEffect(() => {
    setLoading(true)
    getCustomerMenus(category || undefined)
      .then((res) => setMenus(res.data.data))
      .catch(() => setError('메뉴를 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [category])

  const filtered = useMemo(() => {
    if (!keyword.trim()) return menus
    return menus.filter((m) => m.name.includes(keyword.trim()))
  }, [menus, keyword])

  return (
    <CustomerLayout>
      <div className={styles.filterBar}>
        <div className={styles.tabs}>
          {CATEGORIES.map(({ value, label }) => (
            <button
              key={value}
              className={`${styles.tab} ${category === value ? styles.tabActive : ''}`}
              onClick={() => { setCategory(value); setKeyword('') }}
            >
              {label}
            </button>
          ))}
          <button
            className={styles.tabEvent}
            onClick={() => navigate('/events')}
          >
            🎁 한정판매
          </button>
        </div>
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
              className={styles.card}
              onClick={() => navigate(`/menus/${menu.menuId}`)}
            >
              <div className={styles.imageWrap}>
                {menu.imageUrl ? (
                  <img src={menu.imageUrl} alt={menu.name} className={styles.image} />
                ) : (
                  <div className={styles.imagePlaceholder}>☕</div>
                )}
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
