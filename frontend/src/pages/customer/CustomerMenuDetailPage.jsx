import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getCustomerMenu } from '../../api/menu'
import { addToCart } from '../../api/cart'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './CustomerMenuDetailPage.module.css'

export default function CustomerMenuDetailPage() {
  const { menuId } = useParams()
  const navigate = useNavigate()

  const [menu, setMenu] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  // optionGroup → 선택된 옵션 (라디오 방식)
  const [selectedOptions, setSelectedOptions] = useState({})
  const [quantity, setQuantity] = useState(1)
  const [adding, setAdding] = useState(false)

  useEffect(() => {
    getCustomerMenu(menuId)
      .then((res) => setMenu(res.data.data))
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
              <p className={styles.groupLabel}>{group}</p>
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
              disabled={adding}
              onClick={async () => {
                setAdding(true)
                try {
                  await addToCart({
                    menuId: menu.menuId,
                    quantity,
                    selectedOptions: Object.values(selectedOptions).map((o) => ({
                      menuOptionId: o.menuOptionId,
                      optionGroup: o.optionGroup,
                      optionName: o.optionName,
                      additionalPrice: o.additionalPrice,
                    })),
                  })
                  navigate('/cart')
                } catch {
                  alert('장바구니 담기에 실패했습니다')
                } finally {
                  setAdding(false)
                }
              }}
            >
              {adding ? '담는 중...' : '장바구니 담기'}
            </button>
          </div>
        </div>
      </div>
    </CustomerLayout>
  )
}
