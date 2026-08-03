import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { getMyProfile, updateMyProfile, changePassword, withdrawMe } from '../../api/member'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './MyProfilePage.module.css'

// 백엔드 WithdrawReason enum 과 값이 일치해야 한다
const WITHDRAW_REASONS = [
  { value: 'NOT_USING',       label: '자주 이용하지 않음' },
  { value: 'INCONVENIENT',    label: '사용이 불편함' },
  { value: 'PRICE',           label: '가격이 부담됨' },
  { value: 'SERVICE_QUALITY', label: '서비스가 만족스럽지 않음' },
  { value: 'PRIVACY',         label: '개인정보가 걱정됨' },
  { value: 'OTHER',           label: '기타' },
]

/**
 * 고객 내 정보 페이지. (@route /profile)
 *
 * - 기본 정보 카드: 아이디·이름·가입 경로(자체/카카오)·가입일 (읽기 전용)
 * - 정보 수정 폼: 닉네임·전화번호 변경, 성공 시 localStorage의 nickname도 동기화
 * - 비밀번호 변경 폼: 자체 가입(LOCAL) 계정에만 표시, 카카오 가입자는 숨김
 * - 회원 탈퇴: 모달에서 비밀번호(LOCAL만) 확인 + 사유 선택(선택 입력) 후 진행
 */
export default function MyProfilePage() {
  const navigate = useNavigate()
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

  // 탈퇴 모달
  const [wdOpen,    setWdOpen]    = useState(false)
  const [wdForm,    setWdForm]    = useState({ password: '', reason: '' })
  const [wdSaving,  setWdSaving]  = useState(false)
  const [wdError,   setWdError]   = useState('')

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

  const openWithdraw = () => {
    setWdForm({ password: '', reason: '' })
    setWdError('')
    setWdOpen(true)
  }

  const handleWithdraw = async () => {
    setWdError('')
    setWdSaving(true)
    try {
      // reason 은 선택 입력이라 미선택이면 아예 보내지 않는다
      await withdrawMe({
        ...(profile.provider === 'LOCAL' ? { password: wdForm.password } : {}),
        ...(wdForm.reason ? { reason: wdForm.reason } : {}),
      })
      // 탈퇴 즉시 기존 토큰이 무효화되므로 로컬 상태도 함께 비운다
      localStorage.clear()
      // alert 은 확인을 누를 때까지 블로킹되므로, 사용자가 결과를 확인한 뒤 로그인 페이지로 이동한다
      alert('탈퇴 처리되었습니다')
      navigate('/login')
    } catch (err) {
      // 409: 진행 중인 주문 보유 / 400: 비밀번호 불일치
      setWdError(err.response?.data?.message || '탈퇴에 실패했습니다')
      setWdSaving(false)
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

        {/* 회원 탈퇴 */}
        <section className={styles.card}>
          <h2 className={styles.sectionTitle}>회원 탈퇴</h2>
          <p className={styles.withdrawDesc}>
            탈퇴하면 계정 정보가 삭제되며 되돌릴 수 없습니다.
            주문 내역은 매장의 매출 기록으로 남지만 회원 정보와의 연결은 끊깁니다.
          </p>
          <button className={styles.withdrawBtn} type="button" onClick={openWithdraw}>
            회원 탈퇴
          </button>
        </section>
      </div>

      {/* 탈퇴 확인 모달 */}
      {wdOpen && (
        <div className={styles.modalBackdrop} onClick={() => !wdSaving && setWdOpen(false)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>정말 탈퇴하시겠어요?</h2>

            <ul className={styles.warnList}>
              <li>계정 정보(아이디·닉네임·전화번호)가 삭제됩니다</li>
              <li>같은 아이디로 다시 가입할 수 있지만 기존 정보는 복구되지 않습니다</li>
              <li>진행 중인 주문이 있으면 탈퇴할 수 없습니다</li>
            </ul>

            <div className={styles.field}>
              <label className={styles.label}>탈퇴 사유 <span className={styles.optional}>(선택)</span></label>
              <select
                className={styles.input}
                value={wdForm.reason}
                onChange={(e) => setWdForm((p) => ({ ...p, reason: e.target.value }))}
              >
                <option value="">선택 안 함</option>
                {WITHDRAW_REASONS.map((r) => (
                  <option key={r.value} value={r.value}>{r.label}</option>
                ))}
              </select>
            </div>

            {/* 비밀번호 확인 — 자체 가입 계정만 */}
            {profile.provider === 'LOCAL' && (
              <div className={styles.field}>
                <label className={styles.label}>비밀번호 확인</label>
                <input
                  className={styles.input}
                  type="password"
                  value={wdForm.password}
                  onChange={(e) => setWdForm((p) => ({ ...p, password: e.target.value }))}
                  placeholder="본인 확인을 위해 비밀번호를 입력해주세요"
                />
              </div>
            )}

            {wdError && <p className={styles.formError}>{wdError}</p>}

            <div className={styles.modalActions}>
              <button
                className={styles.cancelBtn}
                type="button"
                onClick={() => setWdOpen(false)}
                disabled={wdSaving}
              >
                취소
              </button>
              <button
                className={styles.dangerBtn}
                type="button"
                onClick={handleWithdraw}
                disabled={wdSaving}
              >
                {wdSaving ? '처리 중...' : '탈퇴하기'}
              </button>
            </div>
          </div>
        </div>
      )}
    </CustomerLayout>
  )
}
