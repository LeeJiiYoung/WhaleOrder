import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { signUp } from '../api/auth'
import styles from './SignUpPage.module.css'

const initialForm = {
  userId: '',
  password: '',
  passwordConfirm: '',
  name: '',
  nickname: '',
  phone: '',
}

export default function SignUpPage() {
  const navigate = useNavigate()
  const [form, setForm] = useState(initialForm)
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleChange = (e) => {
    const { name, value } = e.target
    setForm((prev) => ({ ...prev, [name]: value }))
    setErrors((prev) => ({ ...prev, [name]: '' }))
    setServerError('')
  }

  const validate = () => {
    const next = {}
    if (!form.userId) next.userId = '아이디를 입력해주세요'
    else if (form.userId.length < 4 || form.userId.length > 20)
      next.userId = '아이디는 4~20자 사이여야 합니다'

    if (!form.password) next.password = '비밀번호를 입력해주세요'
    else if (form.password.length < 8)
      next.password = '비밀번호는 8자 이상이어야 합니다'

    if (!form.passwordConfirm) next.passwordConfirm = '비밀번호 확인을 입력해주세요'
    else if (form.password !== form.passwordConfirm)
      next.passwordConfirm = '비밀번호가 일치하지 않습니다'

    if (!form.name) next.name = '이름을 입력해주세요'

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
      const { userId, password, name, nickname, phone } = form
      const res = await signUp({ userId, password, name, nickname, phone })
      const { accessToken, refreshToken, nickname: nick, role } = res.data.data
      localStorage.setItem('accessToken', accessToken)
      localStorage.setItem('refreshToken', refreshToken)
      localStorage.setItem('nickname', nick)
      localStorage.setItem('role', role)
      navigate('/')
    } catch (err) {
      const msg = err.response?.data?.message || '회원가입 중 오류가 발생했습니다'
      setServerError(msg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={styles.container}>
      <div className={styles.card}>
        <h1 className={styles.title}>회원가입</h1>

        <form onSubmit={handleSubmit} noValidate>
          <Field
            label="아이디"
            name="userId"
            value={form.userId}
            onChange={handleChange}
            error={errors.userId}
            placeholder="4~20자 영문, 숫자"
          />
          <Field
            label="비밀번호"
            name="password"
            type="password"
            value={form.password}
            onChange={handleChange}
            error={errors.password}
            placeholder="8자 이상"
          />
          <Field
            label="비밀번호 확인"
            name="passwordConfirm"
            type="password"
            value={form.passwordConfirm}
            onChange={handleChange}
            error={errors.passwordConfirm}
            placeholder="비밀번호를 한 번 더 입력해주세요"
          />
          <Field
            label="이름"
            name="name"
            value={form.name}
            onChange={handleChange}
            error={errors.name}
            placeholder="실명을 입력해주세요"
          />
          <Field
            label="닉네임 (선택)"
            name="nickname"
            value={form.nickname}
            onChange={handleChange}
            placeholder="닉네임을 입력해주세요"
          />
          <Field
            label="전화번호 (선택)"
            name="phone"
            value={form.phone}
            onChange={handleChange}
            placeholder="010-0000-0000"
          />

          {serverError && <p className={styles.serverError}>{serverError}</p>}

          <button className={styles.submitBtn} type="submit" disabled={loading}>
            {loading ? '처리 중...' : '가입하기'}
          </button>
        </form>

        <p className={styles.loginLink}>
          이미 계정이 있으신가요? <Link to="/login">로그인</Link>
        </p>
      </div>
    </div>
  )
}

function Field({ label, name, type = 'text', value, onChange, error, placeholder }) {
  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={name}>
        {label}
      </label>
      <input
        className={`${styles.input} ${error ? styles.inputError : ''}`}
        id={name}
        name={name}
        type={type}
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        autoComplete="off"
      />
      {error && <span className={styles.errorMsg}>{error}</span>}
    </div>
  )
}
