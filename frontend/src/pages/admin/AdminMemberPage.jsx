import { useState, useEffect, useCallback } from 'react'
import { getMembers, createMember, updateMember, deleteMember, resetPassword } from '../../api/member'
import AdminLayout from '../../components/admin/AdminLayout'
import styles from './AdminMemberPage.module.css'

const ROLES = [
  { value: '',            label: '전체' },
  { value: 'CUSTOMER',   label: '고객' },
  { value: 'BARISTA',    label: '바리스타' },
  { value: 'STORE_ADMIN',label: '매장 관리자' },
  { value: 'OWNER',      label: '점주' },
  { value: 'ADMIN',      label: '관리자' },
]

const ROLE_BADGE = {
  CUSTOMER:    { text: '고객',      color: '#6b7280', bg: '#f3f4f6' },
  BARISTA:     { text: '바리스타',  color: '#0891b2', bg: '#ecfeff' },
  STORE_ADMIN: { text: '매장관리자',color: '#7c3aed', bg: '#f5f3ff' },
  OWNER:       { text: '점주',      color: '#d97706', bg: '#fffbeb' },
  ADMIN:       { text: '관리자',    color: '#dc2626', bg: '#fef2f2' },
}

const EMPTY_FORM = { userId: '', password: '', name: '', nickname: '', phone: '', role: 'CUSTOMER' }

export default function AdminMemberPage() {
  const [members, setMembers]       = useState([])
  const [loading, setLoading]       = useState(true)
  const [error, setError]           = useState('')
  const [keyword, setKeyword]       = useState('')
  const [roleFilter, setRoleFilter] = useState('')

  const [modal, setModal]           = useState(null)   // null | 'create' | 'edit'
  const [editing, setEditing]       = useState(null)   // 수정 대상 회원
  const [form, setForm]             = useState(EMPTY_FORM)
  const [formError, setFormError]   = useState('')
  const [saving, setSaving]         = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    setError('')
    getMembers(keyword, roleFilter)
      .then((res) => setMembers(res.data.data))
      .catch(() => setError('회원 목록을 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [keyword, roleFilter])

  useEffect(() => { load() }, [load])

  // ── 검색 ────────────────────────────────────────────────────────
  const handleSearch = (e) => {
    e.preventDefault()
    load()
  }

  // ── 모달 열기 ────────────────────────────────────────────────────
  const openCreate = () => {
    setForm(EMPTY_FORM)
    setFormError('')
    setEditing(null)
    setModal('create')
  }

  const openEdit = (member) => {
    setForm({
      userId:   member.userId ?? '',
      password: '',
      name:     member.name ?? '',
      nickname: member.nickname ?? '',
      phone:    member.phone ?? '',
      role:     member.role,
    })
    setFormError('')
    setEditing(member)
    setModal('edit')
  }

  const closeModal = () => { setModal(null); setEditing(null) }

  // ── 저장 ────────────────────────────────────────────────────────
  const handleSave = async () => {
    setFormError('')
    if (!form.name.trim()) { setFormError('이름을 입력해주세요'); return }
    if (modal === 'create') {
      if (form.userId.length < 4)  { setFormError('아이디는 4자 이상이어야 합니다'); return }
      if (form.password.length < 8){ setFormError('비밀번호는 8자 이상이어야 합니다'); return }
    }

    setSaving(true)
    try {
      if (modal === 'create') {
        await createMember(form)
      } else {
        const { userId: _u, password: _p, ...updateData } = form
        await updateMember(editing.memberId, updateData)
      }
      closeModal()
      load()
    } catch (err) {
      setFormError(err.response?.data?.message || '저장에 실패했습니다')
    } finally {
      setSaving(false)
    }
  }

  // ── 비밀번호 초기화 ──────────────────────────────────────────────
  const handleResetPassword = async (member) => {
    if (!window.confirm(`"${member.name}" 회원의 비밀번호를 초기화하시겠습니까?\n초기 비밀번호: ${member.userId}${member.userId}`)) return
    try {
      await resetPassword(member.memberId)
      alert('비밀번호가 초기화됐습니다')
    } catch (err) {
      alert(err.response?.data?.message || '초기화에 실패했습니다')
    }
  }

  // ── 삭제 ────────────────────────────────────────────────────────
  const handleDelete = async (member) => {
    if (!window.confirm(`"${member.name}" 회원을 삭제하시겠습니까?\n삭제된 데이터는 복구할 수 없습니다.`)) return
    try {
      await deleteMember(member.memberId)
      load()
    } catch (err) {
      alert(err.response?.data?.message || '삭제에 실패했습니다')
    }
  }

  const handleFormChange = (e) => setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }))

  // ── 렌더 ────────────────────────────────────────────────────────
  return (
    <AdminLayout>
      <div className={styles.header}>
        <h1 className={styles.title}>회원 관리</h1>
        <button className={styles.createBtn} onClick={openCreate}>+ 새 회원 등록</button>
      </div>

      {/* 검색 / 필터 */}
      <form className={styles.toolbar} onSubmit={handleSearch}>
        <input
          className={styles.searchInput}
          placeholder="아이디 또는 이름 검색"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />
        <select
          className={styles.roleSelect}
          value={roleFilter}
          onChange={(e) => setRoleFilter(e.target.value)}
        >
          {ROLES.map(({ value, label }) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </select>
        <button type="submit" className={styles.searchBtn}>검색</button>
      </form>

      {error && <div className={styles.errorBox}>{error}</div>}

      {/* 테이블 */}
      {!loading && !error && (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th>ID</th>
                <th>아이디</th>
                <th>이름</th>
                <th>닉네임</th>
                <th>전화번호</th>
                <th>역할</th>
                <th>가입 경로</th>
                <th>가입일</th>
                <th>액션</th>
              </tr>
            </thead>
            <tbody>
              {members.length === 0 ? (
                <tr><td colSpan={9} className={styles.empty}>조회된 회원이 없습니다</td></tr>
              ) : members.map((m) => {
                const badge = ROLE_BADGE[m.role] ?? { text: m.role, color: '#888', bg: '#f5f5f5' }
                return (
                  <tr key={m.memberId}>
                    <td className={styles.idCell}>{m.memberId}</td>
                    <td>{m.userId ?? <span className={styles.social}>소셜</span>}</td>
                    <td className={styles.nameCell}>{m.name}</td>
                    <td>{m.nickname ?? '-'}</td>
                    <td>{m.phone ?? '-'}</td>
                    <td>
                      <span className={styles.badge} style={{ color: badge.color, background: badge.bg }}>
                        {badge.text}
                      </span>
                    </td>
                    <td>{m.provider === 'KAKAO' ? '카카오' : '자체'}</td>
                    <td>{new Date(m.createdAt).toLocaleDateString('ko-KR')}</td>
                    <td className={styles.actions}>
                      <button className={styles.editBtn} onClick={() => openEdit(m)}>수정</button>
                      {m.provider !== 'KAKAO' && (
                        <button className={styles.resetBtn} onClick={() => handleResetPassword(m)}>비번초기화</button>
                      )}
                      <button className={styles.deleteBtn} onClick={() => handleDelete(m)}>삭제</button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {loading && <div className={styles.emptyCenter}>불러오는 중...</div>}

      {/* 생성/수정 모달 */}
      {modal && (
        <div className={styles.overlay} onClick={closeModal}>
          <div className={styles.modalBox} onClick={(e) => e.stopPropagation()}>
            <div className={styles.modalHeader}>
              <h2>{modal === 'create' ? '새 회원 등록' : '회원 정보 수정'}</h2>
              <button className={styles.closeBtn} onClick={closeModal}>✕</button>
            </div>

            <div className={styles.modalBody}>
              {modal === 'create' && (
                <>
                  <label className={styles.label}>아이디 *</label>
                  <input className={styles.input} name="userId" value={form.userId}
                    onChange={handleFormChange} placeholder="4~20자" />

                  <label className={styles.label}>비밀번호 *</label>
                  <input className={styles.input} name="password" type="password" value={form.password}
                    onChange={handleFormChange} placeholder="8자 이상" />
                </>
              )}

              <label className={styles.label}>이름 *</label>
              <input className={styles.input} name="name" value={form.name}
                onChange={handleFormChange} placeholder="실명" />

              <label className={styles.label}>닉네임</label>
              <input className={styles.input} name="nickname" value={form.nickname}
                onChange={handleFormChange} placeholder="서비스 표시 이름" />

              <label className={styles.label}>전화번호</label>
              <input className={styles.input} name="phone" value={form.phone}
                onChange={handleFormChange} placeholder="010-0000-0000" />

              <label className={styles.label}>역할 *</label>
              <select className={styles.input} name="role" value={form.role} onChange={handleFormChange}>
                {ROLES.filter((r) => r.value).map(({ value, label }) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>

              {formError && <p className={styles.formError}>{formError}</p>}
            </div>

            <div className={styles.modalFooter}>
              <button className={styles.cancelBtn} onClick={closeModal}>취소</button>
              <button className={styles.saveBtn} onClick={handleSave} disabled={saving}>
                {saving ? '저장 중...' : '저장'}
              </button>
            </div>
          </div>
        </div>
      )}
    </AdminLayout>
  )
}
