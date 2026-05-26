import { useState, useEffect, useRef } from 'react'
import { searchOwners } from '../../api/member'
import styles from './OwnerSearchPopup.module.css'

export default function OwnerSearchPopup({ onSelect, onClose }) {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState([])
  const [loading, setLoading] = useState(true)
  const debounceRef = useRef(null)

  useEffect(() => {
    const onKeyDown = (e) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  useEffect(() => {
    const delay = keyword === '' ? 0 : 300
    clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => fetchOwners(keyword), delay)
    return () => clearTimeout(debounceRef.current)
  }, [keyword])

  const fetchOwners = async (kw) => {
    setLoading(true)
    try {
      const res = await searchOwners(kw)
      setResults(res.data.data)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={styles.overlay}>
      <div className={styles.popup}>

        <div className={styles.header}>
          <span className={styles.title}>점주 검색</span>
          <button className={styles.closeBtn} onClick={onClose}>✕</button>
        </div>

        <div className={styles.searchRow}>
          <div className={styles.inputWrap}>
            <span className={styles.searchIcon}>
              {loading ? <span className={styles.spinner}>⏳</span> : '🔍'}
            </span>
            <input
              className={styles.searchInput}
              placeholder="아이디 또는 이름으로 검색"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              autoFocus
            />
          </div>
        </div>

        <div className={styles.divider} />

        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th>아이디</th>
                <th>이름</th>
                <th>연락처</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {results.length === 0 && !loading ? (
                <tr>
                  <td colSpan={4} className={styles.empty}>검색 결과가 없습니다</td>
                </tr>
              ) : (
                results.map((m) => (
                  <tr
                    key={m.memberId}
                    className={styles.row}
                    onClick={() => { onSelect(m.userId); onClose() }}
                  >
                    <td className={styles.tdUserId}>{m.userId}</td>
                    <td>{m.name}</td>
                    <td className={styles.tdPhone}>{m.phone || '-'}</td>
                    <td className={styles.tdAction}>
                      <button
                        className={styles.selectBtn}
                        onClick={(e) => { e.stopPropagation(); onSelect(m.userId); onClose() }}
                      >
                        선택
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {!loading && results.length > 0 && (
          <div className={styles.footer}>
            {results.length}명의 점주
          </div>
        )}

      </div>
    </div>
  )
}
