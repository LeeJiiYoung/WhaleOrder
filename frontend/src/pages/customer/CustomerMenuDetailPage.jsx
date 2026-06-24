import { useState, useEffect } from 'react'
import { useParams, useNavigate, useLocation } from 'react-router-dom'
import { getCustomerMenus } from '../../api/menu'
import { addToCart } from '../../api/cart'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './CustomerMenuDetailPage.module.css'

/**
 * 고객 메뉴 상세 페이지. (@route /menus/:menuId)
 *
 * - 메뉴 이미지·이름·설명·기본 가격 표시
 * - 옵션 그룹별 라디오 선택 (같은 그룹 재클릭 시 선택 해제)
 * - 수량 증감 버튼, 선택 옵션 추가 금액 합산하여 실시간 합계 표시
 * - "장바구니 담기" 클릭 시 선택 옵션·수량과 함께 카트에 추가 후 /cart로 이동
 */
export default function CustomerMenuDetailPage() {
  const { menuId } = useParams()
  const navigate = useNavigate()
  const { state } = useLocation()

  const [menu, setMenu] = useState(state?.menu ?? null)
  const [loading, setLoading] = useState(!state?.menu)
  const [error, setError] = useState('')

  // optionGroup → 선택된 옵션 (라디오 방식)
  const [selectedOptions, setSelectedOptions] = useState({})
  const [quantity, setQuantity] = useState(1)
  const [adding, setAdding] = useState(false)

  // 직접 URL 접근 시 (state 없음) — 매장 메뉴 목록에서 해당 메뉴 탐색
  useEffect(() => {
    if (state?.menu) return
    const storeId = localStorage.getItem('selectedStoreId')
    if (!storeId) { navigate('/stores', { replace: true }); return }
    getCustomerMenus(storeId)
      .then((res) => {
        const found = res.data.data.find((m) => String(m.menuId) === String(menuId))
        if (found) setMenu(found)
        else setError('메뉴 정보를 불러오지 못했습니다')
      })
      .catch(() => setError('메뉴 정보를 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [menuId])

  if (loading) return <CustomerLayout><div className={styles.center}>불러오는 중...</div></CustomerLayout>
  if (error)   return <CustomerLayout><div className={styles.center}>{error}</div></CustomerLayout>
  if (!menu)   return null

  // 옵션을 그룹별로 묶기
  const optionGroups = menu.options.reduce((acc, opt) => {
    if (!acc[opt.optionGroup]) acc[opt.optionGroup] = []
    acc[opt.optionGroup].push(opt)
    return acc
  }, {})

  // 그룹별 필수 여부 (그룹 내 한 행이라도 isRequired=true 면 필수)
  const requiredGroups = Object.entries(optionGroups)
    .filter(([, opts]) => opts.some((o) => o.isRequired))
    .map(([group]) => group)

  const missingRequired = requiredGroups.filter((group) => !selectedOptions[group])
  const canOrder = missingRequired.length === 0

  const handleSelectOption = (group, option) => {
    setSelectedOptions((prev) => {
      // 이미 선택된 옵션을 다시 누르면 선택 해제
      if (prev[group]?.menuOptionId === option.menuOptionId) {
        const next = { ...prev }
        delete next[group]
        return next
      }
      return { ...prev, [group]: option }
    })
  }

  const extraPrice = Object.values(selectedOptions).reduce(
    (sum, opt) => sum + opt.additionalPrice,
    0
  )
  const totalPrice = menu.basePrice + extraPrice

  return (
    <CustomerLayout>
      <button className={styles.backBtn} onClick={() => navigate('/menus')}>
        ← 메뉴 목록
      </button>

      <div className={styles.layout}>
        {/* 이미지 */}
        <div className={styles.imageSection}>
          {menu.imageUrl ? (
            <img src={menu.imageUrl} alt={menu.name} className={styles.image} />
          ) : (
            <div className={styles.imagePlaceholder}>☕</div>
          )}
        </div>

        {/* 정보 + 옵션 */}
        <div className={styles.infoSection}>
          <h1 className={styles.menuName}>{menu.name}</h1>
          {menu.description && (
            <p className={styles.menuDesc}>{menu.description}</p>
          )}
          <p className={styles.basePrice}>{menu.basePrice.toLocaleString()}원</p>

          {/* 옵션 그룹 */}
          {Object.entries(optionGroups).map(([group, options]) => (
            <div key={group} className={styles.optionGroup}>
              <p className={styles.groupLabel}>
                {group}
                {requiredGroups.includes(group) && <span className={styles.requiredMark}> *</span>}
              </p>
              <div className={styles.optionList}>
                {options.map((opt) => {
                  const isSelected = selectedOptions[group]?.menuOptionId === opt.menuOptionId
                  return (
                    <button
                      key={opt.menuOptionId}
                      className={`${styles.optionBtn} ${isSelected ? styles.optionSelected : ''}`}
                      onClick={() => handleSelectOption(group, opt)}
                    >
                      <span className={styles.optionName}>{opt.optionName}</span>
                      {opt.additionalPrice > 0 && (
                        <span className={styles.optionPrice}>
                          +{opt.additionalPrice.toLocaleString()}원
                        </span>
                      )}
                    </button>
                  )
                })}
              </div>
            </div>
          ))}

          {/* 수량 */}
          <div className={styles.quantityRow}>
            <span className={styles.quantityLabel}>수량</span>
            <div className={styles.quantityControl}>
              <button className={styles.qBtn} onClick={() => setQuantity((q) => Math.max(1, q - 1))}>−</button>
              <span className={styles.qValue}>{quantity}</span>
              <button className={styles.qBtn} onClick={() => setQuantity((q) => q + 1)}>+</button>
            </div>
          </div>

          {/* 주문 버튼 */}
          <div className={styles.orderArea}>
            <div className={styles.totalPrice}>
              <span className={styles.totalLabel}>합계</span>
              <span className={styles.totalAmount}>{totalPrice.toLocaleString()}원</span>
            </div>
            <button
              className={styles.orderBtn}
              disabled={adding || !canOrder}
              onClick={async () => {
                const storeId = localStorage.getItem('selectedStoreId')
                if (!storeId) {
                  alert('매장이 선택되지 않았습니다')
                  navigate('/stores')
                  return
                }
                setAdding(true)
                const payload = {
                  storeId: Number(storeId),
                  menuId: menu.menuId,
                  quantity,
                  selectedOptions: Object.values(selectedOptions).map((o) => ({
                    menuOptionId: o.menuOptionId,
                    optionGroup: o.optionGroup,
                    optionName: o.optionName,
                    additionalPrice: o.additionalPrice,
                  })),
                }
                try {
                  await addToCart(payload)
                  navigate('/cart')
                } catch (err) {
                  // 412 = 카트에 다른 매장 메뉴가 있음 → 사용자 확인 후 force=true 로 재시도
                  if (err.response?.status === 412) {
                    const confirmed = window.confirm(err.response.data.message)
                    if (confirmed) {
                      try {
                        await addToCart(payload, { force: true })
                        navigate('/cart')
                      } catch (retryErr) {
                        alert(retryErr.response?.data?.message || '장바구니 담기에 실패했습니다')
                      }
                    }
                  } else {
                    alert(err.response?.data?.message || '장바구니 담기에 실패했습니다')
                  }
                } finally {
                  setAdding(false)
                }
              }}
            >
              {adding
                ? '담는 중...'
                : !canOrder
                  ? `필수 선택: ${missingRequired.join(', ')}`
                  : '장바구니 담기'}
            </button>
          </div>
        </div>
      </div>
    </CustomerLayout>
  )
}
