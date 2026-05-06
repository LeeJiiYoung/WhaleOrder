import { useState, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { createMenu } from '../../api/menu'
import AdminLayout from '../../components/admin/AdminLayout'
import styles from './MenuCreatePage.module.css'

const CATEGORIES = [
  { value: 'BEVERAGE', label: '음료' },
  { value: 'FOOD',     label: '푸드' },
  { value: 'DESSERT',  label: '디저트' },
  { value: 'DRINK',    label: '드링크' },
]

const initialForm = {
  name: '',
  description: '',
  basePrice: '',
  category: '',
  saleStartDate: '',
  saleEndDate: '',
}

export default function MenuCreatePage() {
  const navigate = useNavigate()
  const fileInputRef = useRef(null)

  const [form, setForm] = useState(initialForm)
  const [imageFile, setImageFile] = useState(null)
  const [preview, setPreview] = useState(null)
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleChange = (e) => {
    const { name, value } = e.target
    setForm((prev) => ({ ...prev, [name]: value }))
    setErrors((prev) => ({ ...prev, [name]: '' }))
    setServerError('')
  }

  const handleImageChange = (e) => {
    const file = e.target.files[0]
    if (!file) return
    if (!file.type.startsWith('image/')) {
      setErrors((prev) => ({ ...prev, imageFile: '이미지 파일만 업로드할 수 있습니다' }))
      return
    }
    if (file.size > 10 * 1024 * 1024) {
      setErrors((prev) => ({ ...prev, imageFile: '10MB 이하 파일만 업로드 가능합니다' }))
      return
    }
    setImageFile(file)
    setPreview(URL.createObjectURL(file))
    setErrors((prev) => ({ ...prev, imageFile: '' }))
  }

  const handleDrop = (e) => {
    e.preventDefault()
    const file = e.dataTransfer.files[0]
    if (file) {
      const fakeEvent = { target: { files: [file] } }
      handleImageChange(fakeEvent)
    }
  }

  const validate = () => {
    const next = {}
    if (!form.name.trim()) next.name = '메뉴 이름을 입력해주세요'
    if (!form.basePrice) next.basePrice = '기본 가격을 입력해주세요'
    else if (isNaN(form.basePrice) || Number(form.basePrice) < 0) next.basePrice = '올바른 금액을 입력해주세요'
    if (!form.category) next.category = '카테고리를 선택해주세요'
    if (form.saleStartDate && form.saleEndDate && form.saleEndDate < form.saleStartDate) {
      next.saleEndDate = '종료일은 시작일 이후여야 합니다'
    }
    return next
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    const errs = validate()
    if (Object.keys(errs).length > 0) { setErrors(errs); return }

    const formData = new FormData()
    formData.append('name', form.name.trim())
    if (form.description) formData.append('description', form.description)
    formData.append('basePrice', form.basePrice)
    formData.append('category', form.category)
    if (form.saleStartDate) formData.append('saleStartDate', form.saleStartDate)
    if (form.saleEndDate)   formData.append('saleEndDate', form.saleEndDate)
    if (imageFile)          formData.append('imageFile', imageFile)

    setLoading(true)
    try {
      const res = await createMenu(formData)
      const { menuId } = res.data.data
      navigate(`/admin/menus/${menuId}`)
    } catch (err) {
      setServerError(err.response?.data?.message || '메뉴 등록 중 오류가 발생했습니다')
    } finally {
      setLoading(false)
    }
  }

  const handleReset = () => {
    setForm(initialForm)
    setImageFile(null)
    setPreview(null)
    setErrors({})
    setServerError('')
  }

  return (
    <AdminLayout>
      <h1 className={styles.pageTitle}>메뉴 등록</h1>

      <div className={styles.layout}>
        {/* 이미지 업로드 영역 */}
        <div className={styles.imageSection}>
          <p className={styles.sectionTitle}>메뉴 이미지</p>
          <div
            className={`${styles.uploadArea} ${errors.imageFile ? styles.uploadAreaError : ''}`}
            onClick={() => fileInputRef.current?.click()}
            onDragOver={(e) => e.preventDefault()}
            onDrop={handleDrop}
          >
            {preview ? (
              <img src={preview} alt="미리보기" className={styles.previewImage} />
            ) : (
              <div className={styles.uploadPlaceholder}>
                <span className={styles.uploadIcon}>🖼</span>
                <p className={styles.uploadText}>클릭 또는 드래그하여 이미지 업로드</p>
                <p className={styles.uploadHint}>PNG, JPG, WEBP · 최대 10MB</p>
              </div>
            )}
          </div>
          {errors.imageFile && <span className={styles.errorMsg}>{errors.imageFile}</span>}
          {preview && (
            <button
              type="button"
              className={styles.changeImageBtn}
              onClick={() => fileInputRef.current?.click()}
            >
              이미지 변경
            </button>
          )}
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            hidden
            onChange={handleImageChange}
          />
        </div>

        {/* 메뉴 정보 폼 */}
        <div className={styles.formSection}>
          <form onSubmit={handleSubmit} noValidate>
            <p className={styles.sectionTitle}>기본 정보</p>

            <Field label="메뉴 이름" name="name" required
              value={form.name} onChange={handleChange} error={errors.name}
              placeholder="예) 카페라떼" styles={styles} />

            <div className={styles.field}>
              <label className={styles.label}>
                설명
              </label>
              <textarea
                className={styles.textarea}
                name="description"
                value={form.description}
                onChange={handleChange}
                placeholder="메뉴 설명 (선택)"
                rows={3}
              />
            </div>

            <div className={styles.formRow}>
              <Field label="기본 가격 (원)" name="basePrice" type="number" required
                value={form.basePrice} onChange={handleChange} error={errors.basePrice}
                placeholder="예) 5500" styles={styles} />

              <div className={styles.field}>
                <label className={styles.label}>
                  카테고리 <span className={styles.required}> *</span>
                </label>
                <select
                  className={`${styles.input} ${errors.category ? styles.inputError : ''}`}
                  name="category"
                  value={form.category}
                  onChange={handleChange}
                >
                  <option value="">선택하세요</option>
                  {CATEGORIES.map(({ value, label }) => (
                    <option key={value} value={value}>{label}</option>
                  ))}
                </select>
                {errors.category && <span className={styles.errorMsg}>{errors.category}</span>}
              </div>
            </div>

            <p className={styles.sectionTitle}>판매 기간 (선택)</p>
            <div className={styles.formRow}>
              <Field label="판매 시작일" name="saleStartDate" type="date"
                value={form.saleStartDate} onChange={handleChange}
                styles={styles} />
              <Field label="판매 종료일" name="saleEndDate" type="date"
                value={form.saleEndDate} onChange={handleChange} error={errors.saleEndDate}
                styles={styles} />
            </div>
            <p className={styles.hint}>비워두면 상시 판매됩니다</p>

            {serverError && <p className={styles.alertError}>{serverError}</p>}

            <div className={styles.submitArea}>
              <button type="button" className={styles.resetBtn} onClick={handleReset}>
                초기화
              </button>
              <button type="submit" className={styles.submitBtn} disabled={loading}>
                {loading ? '등록 중...' : '메뉴 등록'}
              </button>
            </div>
          </form>
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
