import { useState, useEffect } from 'react'
import { getMyProfile, updateMyProfile, changePassword } from '../../api/member'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './MyProfilePage.module.css'

/**
 * 고객 내 정보 페이지. (@route /profile)
 *
 * - 기본 정보 카드: 아이디·이름·가입 경로(자체/카카오)·가입일 (읽기 전용)
 * - 정보 수정 폼: 닉네임·전화번호 변경, 성공 시 localStorage의 nickname도 동기화
 * - 비밀번호 변경 폼: 자체 가입(LOCAL) 계정에만 표시, 카카오 가입자는 숨김
 */
export default function MyProfilePage() {
  const [profile,  setProfile]  = useState(null)
  const [loading,  setLoading]  = useState(true)
  const [msg,      setMsg]      = useState({ text: '', type: '' })

  // 정보 수정 폼
  const [form,     setForm]     = useState({ nickname: '', phone: '' })
  const [saving,   setSaving]   = useState(false)

  // 비밀번호 변경 폼
  const [pwForm,   setPwForm]   = useState({ currentPassword: '', newPassword: '', confirm: '' })
  const [pwSaving, setPwSaving] = useState(false)
  const [pwError,  setPwError]  = useState('')

  const flash = (text, type = 'success') => {
    setMsg({ text, type })
    setTimeout(() => setMsg({ text: '', type: '' }), 3000)
  }

  useEffect(() => {
    getMyProfile()
      .then((res) => {
        const p = res.data.data
        setProfile(p)
        setForm({ nickname: p.nickname ?? '', phone: p.phone ?? '' })
      })
      .catch(() => flash('정보를 불러오지 못했습니다', 'error'))
      .finally(() => setLoading(false))
  }, [])

  const handleSave = async (e) => {
    e.preventDefault()
    setSaving(true)
    try {
      const res = await updateMyProfile(form)
      const updated = res.data.data
      setProfile(updated)
      // 닉네임이 바뀌면 localStorage도 동기화
      if (updated.nickname) localStorage.setItem('nickname', updated.nickname)
      flash('정보가 수정됐습니다')
    } catch (err) {
      flash(err.response?.data?.message || '수정에 실패했습니다', 'error')
    } finally {
      setSaving(false)
    }
  }

  const handlePasswordChange = async (e) => {
    e.preventDefault()
    setPwError('')
    if (pwForm.newPassword !== pwForm.confirm) {
      setPwError('새 비밀번호가 일치하지 않습니다')
      return
    }
    setPwSaving(true)
    try {
      await changePassword({ currentPassword: pwForm.currentPassword, newPassword: pwForm.newPassword })
      setPwForm({ currentPassword: '', newPassword: '', confirm: '' })
      flash('비밀번호가 변경됐습니다')
    } catch (err) {
      setPwError(err.response?.data?.message || '비밀번호 변경에 실패했습니다')
    } finally {
      setPwSaving(false)
    }
  }

  if (loading) return <CustomerLayout><p className={styles.center}>불러오는 중...</p></CustomerLayout>

  return (
    <CustomerLayout>
      <div className={styles.page}>
        <h1 className={styles.title}>내 정보</h1>

        {msg.text && (
          <div className={`${styles.toast} ${msg.type === 'error' ? styles.toastError : styles.toastSuccess}`}>
            {msg.text}
          </div>
        )}

        {/* 기본 정보 카드 */}
        <section className={styles.card}>
          <h2 className={styles.sectionTitle}>기본 정보</h2>
          <div className={styles.infoGrid}>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>아이디</span>
              <span className={styles.infoValue}>{profile.userId ?? '-'}</span>
            </div>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>이름</span>
              <span className={styles.infoValue}>{profile.name}</span>
            </div>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>가입 경로</span>
              <span className={styles.infoValue}>{profile.provider === 'KAKAO' ? '카카오' : '자체 가입'}</span>
            </div>
            <div className={styles.infoRow}>
              <span className={styles.infoLabel}>가입일</span>
              <span className={styles.infoValue}>{new Date(profile.createdAt).toLocaleDateString('ko-KR')}</span>
            </div>
          </div>
        </section>

        {/* 수정 폼 */}
        <section className={styles.card}>
          <h2 className={styles.sectionTitle}>정보 수정</h2>
          <form onSubmit={handleSave} className={styles.form}>
            <div className={styles.field}>
              <label className={styles.label}>닉네임</label>
              <input
                className={styles.input}
                value={form.nickname}
                onChange={(e) => setForm((p) => ({ ...p, nickname: e.target.value }))}
                placeholder="서비스 표시 이름"
              />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>전화번호</label>
              <input
                className={styles.input}
                value={form.phone}
                onChange={(e) => setForm((p) => ({ ...p, phone: e.target.value }))}
                placeholder="010-0000-0000"
              />
            </div>
            <button className={styles.saveBtn} type="submit" disabled={saving}>
              {saving ? '저장 중...' : '저장'}
            </button>
          </form>
        </section>

        {/* 비밀번호 변경 — 자체 가입 계정만 */}
        {profile.provider === 'LOCAL' && (
          <section className={styles.card}>
            <h2 className={styles.sectionTitle}>비밀번호 변경</h2>
            <form onSubmit={handlePasswordChange} className={styles.form}>
              <div className={styles.field}>
                <label className={styles.label}>현재 비밀번호</label>
                <input
                  className={styles.input}
                  type="password"
                  value={pwForm.currentPassword}
                  onChange={(e) => setPwForm((p) => ({ ...p, currentPassword: e.target.value }))}
                  placeholder="현재 비밀번호 입력"
                />
              </div>
              <div className={styles.field}>
                <label className={styles.label}>새 비밀번호</label>
                <input
                  className={styles.input}
                  type="password"
                  value={pwForm.newPassword}
                  onChange={(e) => setPwForm((p) => ({ ...p, newPassword: e.target.value }))}
                  placeholder="8자 이상"
                />
              </div>
              <div className={styles.field}>
                <label className={styles.label}>새 비밀번호 확인</label>
                <input
                  className={styles.input}
                  type="password"
                  value={pwForm.confirm}
                  onChange={(e) => setPwForm((p) => ({ ...p, confirm: e.target.value }))}
                  placeholder="새 비밀번호 재입력"
                />
              </div>
              {pwError && <p className={styles.formError}>{pwError}</p>}
              <button className={styles.saveBtn} type="submit" disabled={pwSaving}>
                {pwSaving ? '변경 중...' : '비밀번호 변경'}
              </button>
            </form>
          </section>
        )}
      </div>
    </CustomerLayout>
  )
}
