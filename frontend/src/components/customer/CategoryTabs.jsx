import { useNavigate } from 'react-router-dom'
import styles from './CategoryTabs.module.css'

const CATEGORIES = [
  { value: '',         label: '전체' },
  { value: 'BEVERAGE', label: '음료' },
  { value: 'FOOD',     label: '푸드' },
  { value: 'DESSERT',  label: '디저트' },
  { value: 'DRINK',    label: '드링크' },
]

/**
 * 고객 카테고리 탭 바.
 *
 * - 메뉴 카테고리 탭 클릭 → `/menus?category=...` 로 이동 (전체는 쿼리 생략)
 * - 한정판매 탭 클릭 → `/events` 로 이동
 * - `current` 값으로 활성 탭 표시 ('' | 'BEVERAGE' | 'FOOD' | 'DESSERT' | 'DRINK' | 'EVENT')
 *
 * @param {{ current: string }} props
 */
export default function CategoryTabs({ current }) {
  const navigate = useNavigate()

  const goToMenu = (value) => {
    navigate(value ? `/menus?category=${value}` : '/menus')
  }

  return (
    <div className={styles.tabs}>
      {CATEGORIES.map(({ value, label }) => (
        <button
          key={value}
          className={`${styles.tab} ${current === value ? styles.tabActive : ''}`}
          onClick={() => goToMenu(value)}
        >
          {label}
        </button>
      ))}
      <button
        className={`${styles.tabEvent} ${current === 'EVENT' ? styles.tabEventActive : ''}`}
        onClick={() => navigate('/events')}
      >
        🎁 한정판매
      </button>
    </div>
  )
}