import { useState, useEffect } from 'react'
import { createStore } from '../../api/store'
import AdminLayout from '../../components/admin/AdminLayout'
import OwnerSearchPopup from '../../components/admin/OwnerSearchPopup'
import styles from './StoreCreatePage.module.css'

const initialForm = {
  name: '',
  postalCode: '',
  address: '',
  addressDetail: '',
  phone: '',
  openTime: '',
  closeTime: '',
  ownerUserId: '',
}

export default function StoreCreatePage() {
  const [form, setForm] = useState(initialForm)
  const [errors, setErrors] = useState({})
  const [successMsg, setSuccessMsg] = useState('')
  const [serverError, setServerError] = useState('')
  const [loading, setLoading] = useState(false)
  const [showOwnerPopup, setShowOwnerPopup] = useState(false)

  useEffect(() => {
    const script = document.createElement('script')
    script.src = 'https://t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js'
    script.async = true
    document.head.appendChild(script)
    return () => document.head.removeChild(script)
  }, [])

  const openPostcode = () => {
    new window.daum.Postcode({
      oncomplete(data) {
        setForm((prev) => ({
          ...prev,
          postalCode: data.zonecode,
          address: data.address,
        }))
        setErrors((prev) => ({ ...prev, postalCode: '', address: '' }))
      },
    }).open()
  }

  const formatPhone = (raw) => {
    const digits = raw.replace(/\D/g, '').slice(0, 11)
    if (digits.startsWith('02')) {
      if (digits.length <= 2)  return digits
      if (digits.length <= 6)  return `${digits.slice(0, 2)}-${digits.slice(2)}`
      if (digits.length <= 9)  return `${digits.slice(0, 2)}-${digits.slice(2, 5)}-${digits.slice(5)}`
      return `${digits.slice(0, 2)}-${digits.slice(2, 6)}-${digits.slice(6, 10)}`
    }
    if (digits.length <= 3)  return digits
    if (digits.length <= 6)  return `${digits.slice(0, 3)}-${digits.slice(3)}`
    if (digits.length <= 10) return `${digits.slice(0, 3)}-${digits.slice(3, 6)}-${digits.slice(6)}`
    return `${digits.slice(0, 3)}-${digits.slice(3, 7)}-${digits.slice(7, 11)}`
  }

  const handleChange = (e) => {
    const { name, value } = e.target
    setForm((prev) => ({ ...prev, [name]: value }))
    setErrors((prev) => ({ ...prev, [name]: '' }))
    setSuccessMsg('')
    setServerError('')
  }

  const validate = () => {
    const next = {}
    if (!form.name) next.name = '매장명을 입력해주세요'
    if (!form.postalCode) next.postalCode = '우편번호를 입력해주세요'
    if (!form.address) next.address = '주소를 입력해주세요'
    if (!form.openTime) next.openTime = '영업 시작 시간을 입력해주세요'
    if (!form.closeTime) next.closeTime = '영업 종료 시간을 입력해주세요'
    if (!form.ownerUserId) next.ownerUserId = '점주를 선택해주세요'
    return next
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    const validationErrors = validate()
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors)
      return
    }

    setLoading(true)
    try {
      const payload = {
        ...form,
        addressDetail: form.addressDetail || null,
        phone: form.phone || null,
      }
      const res = await createStore(payload)
      const { storeId, name } = res.data.data
      setSuccessMsg(`매장이 생성됐습니다! (ID: ${storeId}, 매장명: ${name})`)
      setForm(initialForm)
    } catch (err) {
      const msg = err.response?.data?.message || '매장 생성 중 오류가 발생했습니다'
      setServerError(msg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <AdminLayout>
      <h1 className={styles.pageTitle}>매장 생성</h1>

      <div className={styles.card}>
        <form onSubmit={handleSubmit} noValidate>

          <p className={styles.sectionTitle}>기본 정보</p>
          <div className={styles.formRow}>
            <Field label="매장명" name="name" required
              value={form.name} onChange={handleChange} error={errors.name}
              placeholder="예) 스타벅스 강남점" styles={styles} />
            <Field label="전화번호" name="phone"
              value={form.phone}
              onChange={(e) => setForm((prev) => ({ ...prev, phone: formatPhone(e.target.value) }))}
              placeholder="숫자만 입력하면 자동 포맷됩니다" styles={styles} />
          </div>

          <p className={styles.sectionTitle}>주소 정보</p>
          <div className={styles.field}>
            <label className={styles.label}>
              우편번호 <span className={styles.required}> *</span>
            </label>
            <div className={styles.ownerRow}>
              <input
                className={`${styles.input} ${errors.postalCode ? styles.inputError : ''}`}
                value={form.postalCode}
                placeholder="우편번호 검색 버튼을 눌러주세요"
                readOnly
              />
              <button type="button" className={styles.searchIconBtn} onClick={openPostcode}>
                🔍 주소 검색
              </button>
            </div>
            {errors.postalCode && <span className={styles.errorMsg}>{errors.postalCode}</span>}
          </div>
          <div className={styles.field}>
            <label className={styles.label}>
              도로명 주소 <span className={styles.required}> *</span>
            </label>
            <input
              className={`${styles.input} ${errors.address ? styles.inputError : ''}`}
              value={form.address}
              placeholder="주소 검색 후 자동 입력됩니다"
              readOnly
            />
            {errors.address && <span className={styles.errorMsg}>{errors.address}</span>}
          </div>
          <Field label="상세 주소" name="addressDetail"
            value={form.addressDetail} onChange={handleChange}
            placeholder="예) 2층 201호" styles={styles} />

          <p className={styles.sectionTitle}>영업 시간</p>
          <div className={styles.formRow}>
            <Field label="영업 시작" name="openTime" type="time" required
              value={form.openTime} onChange={handleChange} error={errors.openTime}
              styles={styles} />
            <Field label="영업 종료" name="closeTime" type="time" required
              value={form.closeTime} onChange={handleChange} error={errors.closeTime}
              styles={styles} />
          </div>

          <p className={styles.sectionTitle}>점주 정보</p>
          <div className={styles.field}>
            <label className={styles.label}>
              점주 아이디 <span className={styles.required}> *</span>
            </label>
            <div className={styles.ownerRow}>
              <input
                className={`${styles.input} ${errors.ownerUserId ? styles.inputError : ''}`}
                name="ownerUserId"
                value={form.ownerUserId}
                onChange={handleChange}
                placeholder="점주 회원 아이디"
                autoComplete="off"
                readOnly
              />
              <button
                type="button"
                className={styles.searchIconBtn}
                onClick={() => setShowOwnerPopup(true)}
                title="점주 검색"
              >
                🔍
              </button>
            </div>
            {errors.ownerUserId && <span className={styles.errorMsg}>{errors.ownerUserId}</span>}
          </div>

          <div className={styles.submitArea}>
            <button
              type="button"
              className={styles.resetBtn}
              onClick={() => { setForm(initialForm); setErrors({}); setSuccessMsg(''); setServerError('') }}
            >
              초기화
            </button>
            <button type="submit" className={styles.submitBtn} disabled={loading}>
              {loading ? '생성 중...' : '매장 생성'}
            </button>
          </div>
        </form>

        {successMsg && <p className={styles.alertSuccess}>{successMsg}</p>}
        {serverError && <p className={styles.alertError}>{serverError}</p>}
      </div>

      {showOwnerPopup && (
        <OwnerSearchPopup
          onSelect={(userId) => {
            setForm((prev) => ({ ...prev, ownerUserId: userId }))
            setErrors((prev) => ({ ...prev, ownerUserId: '' }))
          }}
          onClose={() => setShowOwnerPopup(false)}
        />
      )}
    </AdminLayout>
  )
}

function Field({ label, name, type = 'text', required, value, onChange, error, placeholder, maxLength, styles }) {
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
        maxLength={maxLength}
        autoComplete="off"
      />
      {error && <span className={styles.errorMsg}>{error}</span>}
    </div>
  )
}
