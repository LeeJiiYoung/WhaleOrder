import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getStocks, setStock } from '../../api/stock'
import { getMyStores, getStore } from '../../api/store'
import AdminLayout from '../../components/admin/AdminLayout'
import Breadcrumb from '../../components/admin/Breadcrumb'
import styles from './StockPage.module.css'

const CATEGORY_LABEL = {
  COFFEE: '커피',
  NON_COFFEE: '논커피',
  FOOD: '푸드',
  MERCHANDISE: '상품',
}

/**
 * 관리자 재고 관리 페이지. (@route /admin/stores/:storeId/stocks)
 *
 * - 해당 매장의 전체 메뉴 재고를 카테고리별로 그룹화해 표시
 * - 수량 입력창: 빈 값 = 무제한(-1), 0 = 품절, 양수 = 실제 재고
 * - 메뉴별 독립 저장 버튼으로 각각 즉시 반영
 */
export default function StockPage() {
  const { storeId } = useParams()
  const navigate = useNavigate()
  const isOwner = localStorage.getItem('role') === 'OWNER'
  const [storeName, setStoreName] = useState('')
  const [stocks, setStocks] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [edits, setEdits] = useState({})   // menuId → 입력값 문자열
  const [saving, setSaving] = useState({}) // menuId → boolean

  const load = useCallback(() => {
    setLoading(true)
    getStocks(storeId)
      .then((res) => {
        setStocks(res.data.data)
        // 편집 초기값: 무제한이면 빈 문자열, 아니면 수량
        const initial = {}
        res.data.data.forEach((s) => {
          initial[s.menuId] = s.unlimited ? '' : String(s.quantity)
        })
        setEdits(initial)
      })
      .catch(() => setError('재고 정보를 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [storeId])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    if (isOwner) {
      getMyStores().then((res) => {
        const found = res.data.data.find((s) => String(s.storeId) === String(storeId))
        if (found) setStoreName(found.name)
      }).catch(() => {})
    } else {
      getStore(storeId).then((res) => {
        setStoreName(res.data.data.name)
      }).catch(() => {})
    }
  }, [storeId, isOwner])

  const handleSave = async (menuId) => {
    setSaving((prev) => ({ ...prev, [menuId]: true }))
    const raw = edits[menuId]
    const quantity = raw === '' ? null : Number(raw)
    try {
      const res = await setStock(storeId, menuId, { quantity })
      const updated = res.data.data
      setStocks((prev) => prev.map((s) => s.menuId === menuId ? updated : s))
    } catch (err) {
      alert(err.response?.data?.message || '저장에 실패했습니다')
    } finally {
      setSaving((prev) => ({ ...prev, [menuId]: false }))
    }
  }

  const grouped = stocks.reduce((acc, s) => {
    const cat = s.category
    if (!acc[cat]) acc[cat] = []
    acc[cat].push(s)
    return acc
  }, {})

  return (
    <AdminLayout>
      <Breadcrumb items={isOwner
        ? [{ label: '매장 관리' }, { label: '내 매장', path: '/admin/my-stores' }, { label: storeName || '...' }, { label: '재고 관리' }]
        : [{ label: '매장 관리' }, { label: '매장 목록', path: '/admin/stores' }, { label: storeName || '...', path: storeName ? `/admin/stores/${storeId}` : undefined }, { label: '재고 관리' }]
      } />
      <div className={styles.header}>
        <button className={styles.backBtn} onClick={() => navigate(isOwner ? '/admin/my-stores' : `/admin/stores/${storeId}`)}>← 매장으로</button>
        <h1 className={styles.title}>재고 관리</h1>
      </div>
      <p className={styles.desc}>
        수량을 비워두거나 <strong>-1</strong>을 입력하면 <strong>무제한</strong>으로 처리됩니다. 0을 입력하면 <strong>품절</strong>로 표시됩니다.
      </p>

      {loading && <div className={styles.empty}>불러오는 중...</div>}
      {error   && <div className={styles.errorBox}>{error}</div>}

      {!loading && !error && Object.entries(grouped).map(([cat, items]) => (
        <div key={cat} className={styles.section}>
          <p className={styles.catTitle}>{CATEGORY_LABEL[cat] ?? cat}</p>
          <div className={styles.table}>
            <div className={styles.tableHead}>
              <span>메뉴명</span>
              <span>현재 재고</span>
              <span>설정</span>
              <span></span>
            </div>
            {items.map((s) => (
              <div key={s.menuId} className={styles.tableRow}>
                <span className={styles.menuName}>{s.menuName}</span>
                <span className={`${styles.stockBadge} ${s.unlimited ? styles.unlimited : s.quantity === 0 ? styles.soldOut : styles.inStock}`}>
                  {s.unlimited ? '무제한' : s.quantity === 0 ? '품절' : `${s.quantity}개`}
                </span>
                <input
                  className={styles.qtyInput}
                  type="number"
                  min="0"
                  placeholder="무제한"
                  value={edits[s.menuId] ?? ''}
                  onChange={(e) => setEdits((prev) => ({ ...prev, [s.menuId]: e.target.value }))}
                />
                <button
                  className={styles.saveBtn}
                  onClick={() => handleSave(s.menuId)}
                  disabled={saving[s.menuId]}
                >
                  {saving[s.menuId] ? '저장 중' : '저장'}
                </button>
              </div>
            ))}
          </div>
        </div>
      ))}
    </AdminLayout>
  )
}
