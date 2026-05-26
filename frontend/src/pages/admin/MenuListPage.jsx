import { useState, useEffect, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { getMenus, deactivateMenu, activateMenu } from '../../api/menu'
import AdminLayout from '../../components/admin/AdminLayout'
import styles from './MenuListPage.module.css'

const CATEGORIES = [
  { value: '',        label: '전체' },
  { value: 'BEVERAGE', label: '음료' },
  { value: 'FOOD',     label: '푸드' },
  { value: 'DESSERT',  label: '디저트' },
  { value: 'DRINK',    label: '드링크' },
]

const CATEGORY_LABELS = {
  BEVERAGE: '음료',
  FOOD: '푸드',
  DESSERT: '디저트',
  DRINK: '드링크',
}

export default function MenuListPage() {
  const navigate = useNavigate()
  const [menus, setMenus] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [category, setCategory] = useState('')
  const [keyword, setKeyword] = useState('')
  const [togglingId, setTogglingId] = useState(null)

  const load = (cat) => {
    setLoading(true)
    setError('')
    getMenus(cat || undefined)
      .then((res) => setMenus(res.data.data))
      .catch(() => setError('메뉴 목록을 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load(category) }, [category])

  const filtered = useMemo(() => {
    if (!keyword) return menus
    return menus.filter((m) => m.name.includes(keyword))
  }, [menus, keyword])

  const handleToggle = async (menu) => {
    setTogglingId(menu.menuId)
    try {
      if (menu.isActive) {
        await deactivateMenu(menu.menuId)
      } else {
        await activateMenu(menu.menuId)
      }
      load(category)
    } catch {
      alert('상태 변경 중 오류가 발생했습니다')
    } finally {
      setTogglingId(null)
    }
  }

  return (
    <AdminLayout>
      <div className={styles.titleRow}>
        <h1 className={styles.pageTitle}>메뉴 목록</h1>
        <button className={styles.createBtn} onClick={() => navigate('/admin/menu-create')}>
          + 메뉴 등록
        </button>
      </div>

      <div className={styles.filterBar}>
        <div className={styles.categoryTabs}>
          {CATEGORIES.map(({ value, label }) => (
            <button
              key={value}
              className={`${styles.tab} ${category === value ? styles.tabActive : ''}`}
              onClick={() => setCategory(value)}
            >
              {label}
            </button>
          ))}
        </div>
        <input
          className={styles.searchInput}
          placeholder="메뉴명 검색"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />
        <span className={styles.count}>{loading ? '-' : `${filtered.length}개`}</span>
      </div>

      {error && <p className={styles.errorBanner}>{error}</p>}

      {loading ? (
        <div className={styles.emptyState}>불러오는 중...</div>
      ) : filtered.length === 0 ? (
        <div className={styles.emptyState}>등록된 메뉴가 없습니다</div>
      ) : (
        <div className={styles.grid}>
          {filtered.map((menu) => (
            <MenuCard
              key={menu.menuId}
              menu={menu}
              toggling={togglingId === menu.menuId}
              onEdit={() => navigate(`/admin/menus/${menu.menuId}`)}
              onToggle={() => handleToggle(menu)}
            />
          ))}
        </div>
      )}
    </AdminLayout>
  )
}

function MenuCard({ menu, toggling, onEdit, onToggle }) {
  return (
    <div className={`${styles.card} ${!menu.isActive ? styles.cardInactive : ''}`}>
      <div className={styles.imageWrap} onClick={onEdit}>
        {menu.imageUrl ? (
          <img src={menu.imageUrl} alt={menu.name} className={styles.image} />
        ) : (
          <div className={styles.imagePlaceholder}>이미지 없음</div>
        )}
        {!menu.isActive && <div className={styles.inactiveDim}>비활성</div>}
      </div>

      <div className={styles.cardBody}>
        <div className={styles.badges}>
          <span className={styles.categoryBadge}>
            {CATEGORY_LABELS[menu.category] ?? menu.category}
          </span>
          {menu.isOnSale ? (
            <span className={styles.saleBadge}>판매 중</span>
          ) : (
            <span className={styles.noSaleBadge}>판매 중단</span>
          )}
        </div>
        <p className={styles.menuName} onClick={onEdit}>{menu.name}</p>
        <p className={styles.menuPrice}>{menu.basePrice.toLocaleString()}원</p>
      </div>

      <div className={styles.cardFooter}>
        <button className={styles.editBtn} onClick={onEdit}>수정</button>
        <button
          className={`${styles.toggleBtn} ${menu.isActive ? styles.deactivateBtn : styles.activateBtn}`}
          onClick={onToggle}
          disabled={toggling}
        >
          {toggling ? '...' : menu.isActive ? '비활성화' : '활성화'}
        </button>
      </div>
    </div>
  )
}
