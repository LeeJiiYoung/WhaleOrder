import { useState, useEffect, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  getMenu, updateMenu,
  addOption, deleteOption,
} from '../../api/menu'
import AdminLayout from '../../components/admin/AdminLayout'
import styles from './MenuDetailPage.module.css'

const CATEGORIES = [
  { value: 'BEVERAGE', label: '음료' },
  { value: 'FOOD',     label: '푸드' },
  { value: 'DESSERT',  label: '디저트' },
  { value: 'DRINK',    label: '드링크' },
]

const OPTION_GROUPS = ['SIZE', 'SHOT', 'SYRUP', 'TEMPERATURE', '기타']

export default function MenuDetailPage() {
  const { menuId } = useParams()
  const navigate = useNavigate()
  const fileInputRef = useRef(null)

  const [menu, setMenu] = useState(null)
  const [options, setOptions] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  // 수정 폼 상태
  const [form, setForm] = useState(null)
  const [imageFile, setImageFile] = useState(null)
  const [preview, setPreview] = useState(null)
  const [formErrors, setFormErrors] = useState({})
  const [saving, setSaving] = useState(false)
  const [saveMsg, setSaveMsg] = useState('')
  const [serverError, setServerError] = useState('')

  // 옵션 추가 폼 상태
  const [newOption, setNewOption] = useState({ optionGroup: '', optionName: '', additionalPrice: '' })
  const [optionErrors, setOptionErrors] = useState({})
  const [addingOption, setAddingOption] = useState(false)

  const load = () => {
    setLoading(true)
    getMenu(menuId)
      .then((res) => {
        const data = res.data.data
        setMenu(data)
        setOptions(data.options || [])
        setForm({
          name: data.name,
          description: data.description || '',
          basePrice: String(data.basePrice),
          category: data.category,
          saleStartDate: data.saleStartDate || '',
          saleEndDate: data.saleEndDate || '',
        })
        setPreview(data.imageUrl || null)
      })
      .catch(() => setError('메뉴 정보를 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [menuId])

  const handleChange = (e) => {
    const { name, value } = e.target
    setForm((prev) => ({ ...prev, [name]: value }))
    setFormErrors((prev) => ({ ...prev, [name]: '' }))
    setSaveMsg('')
    setServerError('')
  }

  const handleImageChange = (e) => {
    const file = e.target.files[0]
    if (!file) return
    if (!file.type.startsWith('image/')) {
      setFormErrors((prev) => ({ ...prev, imageFile: '이미지 파일만 업로드할 수 있습니다' }))
      return
    }
    setImageFile(file)
    setPreview(URL.createObjectURL(file))
    setFormErrors((prev) => ({ ...prev, imageFile: '' }))
  }

  const validate = () => {
    const next = {}
    if (!form.name.trim()) next.name = '메뉴 이름을 입력해주세요'
    if (!form.basePrice) next.basePrice = '기본 가격을 입력해주세요'
    else if (isNaN(form.basePrice) || Number(form.basePrice) < 0) next.basePrice = '올바른 금액을 입력해주세요'
    if (form.saleStartDate && form.saleEndDate && form.saleEndDate < form.saleStartDate) {
      next.saleEndDate = '종료일은 시작일 이후여야 합니다'
    }
    return next
  }

  const handleSave = async (e) => {
    e.preventDefault()
    const errs = validate()
    if (Object.keys(errs).length > 0) { setFormErrors(errs); return }

    const formData = new FormData()
    formData.append('name', form.name.trim())
    if (form.description) formData.append('description', form.description)
    formData.append('basePrice', form.basePrice)
    if (form.saleStartDate) formData.append('saleStartDate', form.saleStartDate)
    if (form.saleEndDate)   formData.append('saleEndDate', form.saleEndDate)
    if (imageFile)          formData.append('imageFile', imageFile)

    setSaving(true)
    try {
      await updateMenu(menuId, formData)
      setImageFile(null)
      setSaveMsg('저장됐습니다')
      load()
    } catch (err) {
      setServerError(err.response?.data?.message || '저장 중 오류가 발생했습니다')
    } finally {
      setSaving(false)
    }
  }

  const handleOptionChange = (e) => {
    const { name, value } = e.target
    setNewOption((prev) => ({ ...prev, [name]: value }))
    setOptionErrors((prev) => ({ ...prev, [name]: '' }))
  }

  const validateOption = () => {
    const next = {}
    if (!newOption.optionGroup.trim()) next.optionGroup = '옵션 그룹을 입력해주세요'
    if (!newOption.optionName.trim()) next.optionName = '옵션 값을 입력해주세요'
    if (!newOption.additionalPrice && newOption.additionalPrice !== '0') next.additionalPrice = '추가 금액을 입력해주세요'
    else if (isNaN(newOption.additionalPrice) || Number(newOption.additionalPrice) < 0) next.additionalPrice = '0 이상의 금액을 입력해주세요'
    return next
  }

  const handleAddOption = async () => {
    const errs = validateOption()
    if (Object.keys(errs).length > 0) { setOptionErrors(errs); return }

    setAddingOption(true)
    try {
      await addOption(menuId, {
        optionGroup: newOption.optionGroup.trim(),
        optionName: newOption.optionName.trim(),
        additionalPrice: Number(newOption.additionalPrice),
      })
      setNewOption({ optionGroup: '', optionName: '', additionalPrice: '' })
      load()
    } catch (err) {
      alert(err.response?.data?.message || '옵션 추가 중 오류가 발생했습니다')
    } finally {
      setAddingOption(false)
    }
  }

  const handleDeleteOption = async (optionId) => {
    if (!window.confirm('이 옵션을 삭제할까요?')) return
    try {
      await deleteOption(menuId, optionId)
      setOptions((prev) => prev.filter((o) => o.menuOptionId !== optionId))
    } catch {
      alert('옵션 삭제 중 오류가 발생했습니다')
    }
  }

  if (loading) return <AdminLayout><div className={styles.center}>불러오는 중...</div></AdminLayout>
  if (error)   return <AdminLayout><div className={styles.center}>{error}</div></AdminLayout>
  if (!form)   return null

  return (
    <AdminLayout>
      <div className={styles.titleRow}>
        <div>
          <button className={styles.backBtn} onClick={() => navigate('/admin/menus')}>← 목록</button>
          <h1 className={styles.pageTitle}>{menu.name}</h1>
        </div>
      </div>

      <div className={styles.layout}>
        {/* 이미지 영역 */}
        <div className={styles.imageSection}>
          <p className={styles.sectionTitle}>메뉴 이미지</p>
          <div
            className={styles.uploadArea}
            onClick={() => fileInputRef.current?.click()}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault()
              const file = e.dataTransfer.files[0]
              if (file) handleImageChange({ target: { files: [file] } })
            }}
          >
            {preview ? (
              <img src={preview} alt="미리보기" className={styles.previewImage} />
            ) : (
              <div className={styles.uploadPlaceholder}>
                <span className={styles.uploadIcon}>🖼</span>
                <p className={styles.uploadText}>클릭하여 이미지 변경</p>
              </div>
            )}
          </div>
          {formErrors.imageFile && <span className={styles.errorMsg}>{formErrors.imageFile}</span>}
          <button
            type="button"
            className={styles.changeImageBtn}
            onClick={() => fileInputRef.current?.click()}
          >
            이미지 변경
          </button>
          <input ref={fileInputRef} type="file" accept="image/*" hidden onChange={handleImageChange} />
        </div>

        {/* 메뉴 수정 폼 */}
        <div className={styles.formSection}>
          <form onSubmit={handleSave} noValidate>
            <p className={styles.sectionTitle}>기본 정보</p>

            <Field label="메뉴 이름" name="name" required
              value={form.name} onChange={handleChange} error={formErrors.name}
              placeholder="예) 카페라떼" styles={styles} />

            <div className={styles.field}>
              <label className={styles.label}>설명</label>
              <textarea
                className={styles.textarea}
                name="description"
                value={form.description}
                onChange={handleChange}
                rows={3}
              />
            </div>

            <div className={styles.formRow}>
              <Field label="기본 가격 (원)" name="basePrice" type="number" required
                value={form.basePrice} onChange={handleChange} error={formErrors.basePrice}
                styles={styles} />

              <div className={styles.field}>
                <label className={styles.label}>카테고리</label>
                <select
                  className={styles.input}
                  name="category"
                  value={form.category}
                  onChange={handleChange}
                >
                  {CATEGORIES.map(({ value, label }) => (
                    <option key={value} value={value}>{label}</option>
                  ))}
                </select>
              </div>
            </div>

            <p className={styles.sectionTitle}>판매 기간</p>
            <div className={styles.formRow}>
              <Field label="판매 시작일" name="saleStartDate" type="date"
                value={form.saleStartDate} onChange={handleChange} styles={styles} />
              <Field label="판매 종료일" name="saleEndDate" type="date"
                value={form.saleEndDate} onChange={handleChange} error={formErrors.saleEndDate}
                styles={styles} />
            </div>
            <p className={styles.hint}>비워두면 상시 판매됩니다</p>

            {serverError && <p className={styles.alertError}>{serverError}</p>}
            {saveMsg    && <p className={styles.alertSuccess}>{saveMsg}</p>}

            <div className={styles.submitArea}>
              <button type="submit" className={styles.submitBtn} disabled={saving}>
                {saving ? '저장 중...' : '변경 사항 저장'}
              </button>
            </div>
          </form>
        </div>
      </div>

      {/* 옵션 관리 */}
      <div className={styles.optionSection}>
        <p className={styles.optionTitle}>옵션 관리</p>

        {options.length === 0 ? (
          <p className={styles.optionEmpty}>등록된 옵션이 없습니다</p>
        ) : (
          <table className={styles.optionTable}>
            <thead>
              <tr>
                <th>옵션 그룹</th>
                <th>옵션 값</th>
                <th>추가 금액</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {options.map((opt) => (
                <tr key={opt.menuOptionId}>
                  <td><span className={styles.groupBadge}>{opt.optionGroup}</span></td>
                  <td>{opt.optionName}</td>
                  <td>{opt.additionalPrice === 0 ? '무료' : `+${opt.additionalPrice.toLocaleString()}원`}</td>
                  <td>
                    <button
                      className={styles.deleteOptionBtn}
                      onClick={() => handleDeleteOption(opt.menuOptionId)}
                    >
                      삭제
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {/* 옵션 추가 폼 */}
        <div className={styles.addOptionRow}>
          <div className={styles.addOptionField}>
            <label className={styles.addOptionLabel}>옵션 그룹</label>
            <input
              className={`${styles.addOptionInput} ${optionErrors.optionGroup ? styles.inputError : ''}`}
              list="optionGroupList"
              name="optionGroup"
              value={newOption.optionGroup}
              onChange={handleOptionChange}
              placeholder="예) SIZE"
            />
            <datalist id="optionGroupList">
              {OPTION_GROUPS.map((g) => <option key={g} value={g} />)}
            </datalist>
            {optionErrors.optionGroup && <span className={styles.errorMsg}>{optionErrors.optionGroup}</span>}
          </div>

          <div className={styles.addOptionField}>
            <label className={styles.addOptionLabel}>옵션 값</label>
            <input
              className={`${styles.addOptionInput} ${optionErrors.optionName ? styles.inputError : ''}`}
              name="optionName"
              value={newOption.optionName}
              onChange={handleOptionChange}
              placeholder="예) TALL"
            />
            {optionErrors.optionName && <span className={styles.errorMsg}>{optionErrors.optionName}</span>}
          </div>

          <div className={styles.addOptionField}>
            <label className={styles.addOptionLabel}>추가 금액 (원)</label>
            <input
              className={`${styles.addOptionInput} ${optionErrors.additionalPrice ? styles.inputError : ''}`}
              name="additionalPrice"
              type="number"
              min="0"
              value={newOption.additionalPrice}
              onChange={handleOptionChange}
              placeholder="0"
            />
            {optionErrors.additionalPrice && <span className={styles.errorMsg}>{optionErrors.additionalPrice}</span>}
          </div>

          <button
            className={styles.addOptionBtn}
            onClick={handleAddOption}
            disabled={addingOption}
          >
            {addingOption ? '추가 중...' : '+ 옵션 추가'}
          </button>
        </div>
      </div>
    </AdminLayout>
  )
}

function Field({ label, name, type = 'text', required, value, onChange, error, placeholder, styles }) {
  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={name}>
        {label}{required && <span className={styles.required}> *</span>}
      </label>
      <input
        className={`${styles.input} ${error ? styles.inputError : ''}`}
        id={name}
        name={name}
        type={type}
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        min={type === 'number' ? 0 : undefined}
        autoComplete="off"
      />
      {error && <span className={styles.errorMsg}>{error}</span>}
    </div>
  )
}
