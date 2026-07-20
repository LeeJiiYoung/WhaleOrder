import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { login } from '../api/auth'
import styles from './SignUpPage.module.css'

const initialForm = { userId: 'admin', password: 'adminadmin' }

/**
 * 로그인 페이지. (@route /login)
 *
 * - 아이디/비밀번호 자체 로그인
 * - 카카오 OAuth2 로그인 (외부 리다이렉트)
 * - 로그인 후 ADMIN은 /admin/store-create, OWNER는 /admin/my-stores, 고객은 / 로 이동
 * - 빠른 테스트를 위한 테스트 계정 버튼(관리자·고객) 제공
 */
export default function LoginPage() {
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
    if (!form.password) next.password = '비밀번호를 입력해주세요'
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
      const res = await login(form)
      const { accessToken, refreshToken, nickname, role } = res.data.data
      localStorage.setItem('accessToken', accessToken)
      localStorage.setItem('refreshToken', refreshToken)
      localStorage.setItem('nickname', nickname)
      localStorage.setItem('role', role)

      if (role === 'ADMIN') {
        navigate('/admin/store-create')
      } else if (role === 'OWNER') {
        navigate('/admin/my-stores')
      } else {
        navigate('/')
      }
    } catch (err) {
      const msg = err.response?.data?.message || '로그인 중 오류가 발생했습니다'
      setServerError(msg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={styles.container}>
      <div className={styles.card}>
        <h1 className={styles.title}>로그인</h1>

        <form onSubmit={handleSubmit} noValidate>
          <Field
            label="아이디"
            name="userId"
            value={form.userId}
            onChange={handleChange}
            error={errors.userId}
            placeholder="아이디를 입력해주세요"
          />
          <Field
            label="비밀번호"
            name="password"
            type="password"
            value={form.password}
            onChange={handleChange}
            error={errors.password}
            placeholder="비밀번호를 입력해주세요"
          />

          {serverError && <p className={styles.serverError}>{serverError}</p>}

          <button className={styles.submitBtn} type="submit" disabled={loading}>
            {loading ? '로그인 중...' : '로그인'}
          </button>
        </form>

        <div className={styles.divider}>
          <span>또는</span>
        </div>

        <button
          type="button"
          className={styles.kakaoBtn}
          onClick={() => { window.location.href = '/oauth2/authorization/kakao' }}
        >
          <img
            src="https://developers.kakao.com/assets/img/about/logos/kakaolink/kakaolink_btn_medium.png"
            alt=""
            className={styles.kakaoIcon}
          />
          카카오로 로그인
        </button>

        <div className={styles.testBtns}>
          <span className={styles.testLabel}>테스트 계정</span>
          <div className={styles.testBtnRow}>
            <button
              className={styles.testBtn}
              onClick={() => setForm({ userId: 'admin', password: 'adminadmin' })}
            >
              관리자
            </button>
            <button
              className={styles.testBtn}
              onClick={() => setForm({ userId: 'owner', password: 'adminadmin' })}
            >
              점주
            </button>
            <button
              className={styles.testBtn}
              onClick={() => setForm({ userId: 'customer', password: 'adminadmin' })}
            >
              고객
            </button>
          </div>
        </div>

        <p className={styles.loginLink}>
          계정이 없으신가요? <Link to="/signup">회원가입</Link>
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
