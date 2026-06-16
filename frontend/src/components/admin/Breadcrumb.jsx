import { useNavigate } from 'react-router-dom'
import styles from './Breadcrumb.module.css'

/**
 * @param {{ items: Array<{ label: string, path?: string }> }} props
 * path가 있는 항목은 클릭 가능한 링크, 없으면 현재 페이지(마지막 항목)
 */
export default function Breadcrumb({ items }) {
  const navigate = useNavigate()
  return (
    <nav className={styles.breadcrumb}>
      {items.map((item, i) => (
        <span key={i} className={styles.item}>
          {i > 0 && <span className={styles.sep}>&gt;</span>}
          {item.path ? (
            <button className={styles.link} onClick={() => navigate(item.path)}>
              {item.label}
            </button>
          ) : (
            <span className={styles.current}>{item.label}</span>
          )}
        </span>
      ))}
    </nav>
  )
}
