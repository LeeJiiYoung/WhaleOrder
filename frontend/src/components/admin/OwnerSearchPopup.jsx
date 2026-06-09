import { useState, useEffect, useRef } from 'react'
import { searchOwners } from '../../api/member'
import styles from './OwnerSearchPopup.module.css'

/**
 * 점주 검색 팝업 컴포넌트. (매장 생성·수정 화면에서 사용)
 *
 * - OWNER 역할 회원만 목록에 표시
 * - 키워드(아이디·이름) 입력 시 300ms 디바운스 후 자동 검색
 * - 초기 진입 시 전체 목록 즉시 로드
 * - ESC 키 또는 배경 클릭으로 닫기
 * @param {{ onSelect: (member: object) => void, onClose: () => void }} props
 */
export default function OwnerSearchPopup({ onSelect, onClose }) {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState([])
  const [loading, setLoading] = useState(true)
  const [fetchError, setFetchError] = useState('')
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
    setFetchError('')
    try {
      const res = await searchOwners(kw)
      setResults(res.data.data)
    } catch (e) {
      setFetchError(`오류 ${e.response?.status ?? ''}: ${e.response?.data?.message ?? e.message}`)
      setResults([])
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
              {fetchError ? (
                <tr>
                  <td colSpan={4} className={styles.empty}>{fetchError}</td>
                </tr>
              ) : results.length === 0 && !loading ? (
                <tr>
                  <td colSpan={4} className={styles.empty}>검색 결과가 없습니다 (OWNER 역할 회원만 표시됩니다)</td>
                </tr>
              ) : (
                results.map((m) => (
                  <tr
                    key={m.memberId}
                    className={styles.row}
                    onClick={() => { onSelect(m); onClose() }}
                  >
                    <td className={styles.tdUserId}>{m.userId}</td>
                    <td>{m.name}</td>
                    <td className={styles.tdPhone}>{m.phone || '-'}</td>
                    <td className={styles.tdAction}>
                      <button
                        className={styles.selectBtn}
                        onClick={(e) => { e.stopPropagation(); onSelect(m); onClose() }}
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
