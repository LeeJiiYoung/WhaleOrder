import { useNavigate, useLocation } from 'react-router-dom'
import styles from './AdminLayout.module.css'

// ADMIN: 전체 관리 기능
const TOP_NAV_ADMIN = [
  { label: '매장 관리', prefixes: ['/admin/store', '/admin/stock'],  path: '/admin/store-create' },
  { label: '메뉴 관리', prefixes: ['/admin/menu'],                   path: '/admin/menus' },
  { label: '주문 관리', prefixes: ['/admin/order'],                  path: '/admin/orders' },
  { label: '한정판매',  prefixes: ['/admin/events', '/admin/goods'], path: '/admin/events' },
  { label: '회원 관리', prefixes: ['/admin/member'],                 path: '/admin/members' },
]

const SIDEBAR_NAV_ADMIN = [
  {
    section: '매장',
    prefixes: ['/admin/store', '/admin/stock'],
    items: [
      { label: '매장 생성',       icon: '🏪', path: '/admin/store-create' },
      { label: '매장 목록',       icon: '📋', path: '/admin/stores' },
      { label: '재고 복구 실패',  icon: '⚠️', path: '/admin/stock-restore-failures' },
    ],
  },
  {
    section: '메뉴',
    prefixes: ['/admin/menu'],
    items: [
      { label: '메뉴 등록', icon: '➕', path: '/admin/menu-create' },
      { label: '메뉴 목록', icon: '🍽️', path: '/admin/menus' },
    ],
  },
  {
    section: '주문',
    prefixes: ['/admin/order'],
    items: [
      { label: '주문 현황', icon: '📦', path: '/admin/orders' },
    ],
  },
  {
    section: '한정판매',
    prefixes: ['/admin/events', '/admin/goods'],
    items: [
      { label: '이벤트 관리', icon: '🎪', path: '/admin/events' },
      { label: '굿즈 관리',   icon: '🎁', path: '/admin/goods' },
    ],
  },
  {
    section: '회원',
    prefixes: ['/admin/member'],
    items: [
      { label: '회원 관리', icon: '👥', path: '/admin/members' },
    ],
  },
]

// OWNER: 본인 매장의 재고 · 주문 관리만 허용 (다른 메뉴는 접근 권한이 없어 숨김)
const TOP_NAV_OWNER = [
  { label: '매장 관리', prefixes: ['/admin/my-stores', '/admin/stores'], path: '/admin/my-stores' },
  { label: '주문 관리', prefixes: ['/admin/order'],                      path: '/admin/orders' },
]

const SIDEBAR_NAV_OWNER = [
  {
    section: '매장',
    prefixes: ['/admin/my-stores', '/admin/stores'],
    items: [
      { label: '내 매장', icon: '🏪', path: '/admin/my-stores' },
    ],
  },
  {
    section: '주문',
    prefixes: ['/admin/order'],
    items: [
      { label: '주문 현황', icon: '📦', path: '/admin/orders' },
    ],
  },
]

/**
 * 관리자 전용 레이아웃 컴포넌트.
 *
 * - 상단 네비게이션: 매장·메뉴·주문·한정판매·회원 관리 탭 (ADMIN)
 * - OWNER는 본인 매장(재고)·주문 관리만 접근 가능하므로 해당 탭만 표시
 * - 좌측 사이드바: 현재 경로에 해당하는 섹션 항목만 표시 (다른 섹션은 숨김)
 * - 우측 상단: 로그인한 닉네임 표시 및 로그아웃 버튼
 * @param {{ children: React.ReactNode }} props
 */
export default function AdminLayout({ children }) {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const nickname = localStorage.getItem('nickname') || '관리자'
  const isOwner = localStorage.getItem('role') === 'OWNER'

  const TOP_NAV = isOwner ? TOP_NAV_OWNER : TOP_NAV_ADMIN
  const SIDEBAR_NAV = isOwner ? SIDEBAR_NAV_OWNER : SIDEBAR_NAV_ADMIN

  const handleLogout = () => {
    localStorage.clear()
    navigate('/login')
  }

  const matchesPrefixes = (prefixes) => prefixes.some((p) => pathname.startsWith(p))

  // 현재 경로에 해당하는 사이드바 섹션만 표시
  const activeSidebar = SIDEBAR_NAV.find((s) => matchesPrefixes(s.prefixes))

  return (
    <div className={styles.root}>
      <header className={styles.header}>
        <span className={styles.brand}>🐋 WhaleOrder</span>

        <nav className={styles.topNav}>
          {TOP_NAV.map(({ label, prefixes, path }) => (
            <button
              key={path}
              className={`${styles.topNavItem} ${matchesPrefixes(prefixes) ? styles.topNavActive : ''}`}
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