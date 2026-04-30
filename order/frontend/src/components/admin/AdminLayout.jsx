import { useNavigate, useLocation } from 'react-router-dom'
import styles from './AdminLayout.module.css'

const TOP_NAV = [
  { label: '매장 관리', prefix: '/admin/store', path: '/admin/store-create' },
  { label: '메뉴 관리', prefix: '/admin/menu',  path: '/admin/menus' },
  { label: '주문 관리', prefix: '/admin/order', path: '/admin/orders' },
  { label: '회원 관리', prefix: '/admin/member', path: '/admin/members' },
]

const SIDEBAR_NAV = [
  {
    section: '매장',
    prefix: '/admin/store',
    items: [
      { label: '매장 생성', icon: '🏪', path: '/admin/store-create' },
      { label: '매장 목록', icon: '📋', path: '/admin/stores' },
    ],
  },
  {
    section: '메뉴',
    prefix: '/admin/menu',
    items: [
      { label: '메뉴 관리', icon: '🍽️', path: '/admin/menus' },
    ],
  },
  {
    section: '주문',
    prefix: '/admin/order',
    items: [
      { label: '주문 현황', icon: '📦', path: '/admin/orders' },
    ],
  },
  {
    section: '회원',
    prefix: '/admin/member',
    items: [
      { label: '회원 관리', icon: '👥', path: '/admin/members' },
    ],
  },
]

export default function AdminLayout({ children }) {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const nickname = localStorage.getItem('nickname') || '관리자'

  const handleLogout = () => {
    localStorage.clear()
    navigate('/login')
  }

  // 현재 경로에 해당하는 사이드바 섹션만 표시
  const activeSidebar = SIDEBAR_NAV.find((s) => pathname.startsWith(s.prefix))

  return (
    <div className={styles.root}>
      <header className={styles.header}>
        <span className={styles.brand}>🐋 WhaleOrder</span>

        <nav className={styles.topNav}>
          {TOP_NAV.map(({ label, prefix, path }) => (
            <button
              key={prefix}
              className={`${styles.topNavItem} ${pathname.startsWith(prefix) ? styles.topNavActive : ''}`}
              onClick={() => navigate(path)}
            >
              {label}
            </button>
          ))}
        </nav>

        <div className={styles.headerRight}>
          <span>{nickname}님</span>
          <button className={styles.logoutBtn} onClick={handleLogout}>
            로그아웃
          </button>
        </div>
      </header>

      <div className={styles.body}>
        <nav className={styles.sidebar}>
          {activeSidebar ? (
            <div className={styles.navSection}>
              <p className={styles.navSectionTitle}>{activeSidebar.section}</p>
              {activeSidebar.items.map(({ label, icon, path }) => (
                <button
                  key={path}
                  className={`${styles.navItem} ${pathname === path ? styles.active : ''}`}
                  onClick={() => navigate(path)}
                >
                  <span className={styles.navIcon}>{icon}</span>
                  {label}
                </button>
              ))}
            </div>
          ) : (
            SIDEBAR_NAV.map(({ section, items }) => (
              <div key={section} className={styles.navSection}>
                <p className={styles.navSectionTitle}>{section}</p>
                {items.map(({ label, icon, path }) => (
                  <button
                    key={path}
                    className={`${styles.navItem} ${pathname === path ? styles.active : ''}`}
                    onClick={() => navigate(path)}
                  >
                    <span className={styles.navIcon}>{icon}</span>
                    {label}
                  </button>
                ))}
              </div>
            ))
          )}
        </nav>

        <main className={styles.content}>
          {children}
        </main>
      </div>
    </div>
  )
}