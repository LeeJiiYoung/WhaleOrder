import client from './client'

export const searchOwners = (keyword = '') =>
  client.get('/admin/members/owners', { params: { keyword } })

// 내 정보 (고객 본인)
export const getMyProfile    = ()      => client.get('/members/me')
export const updateMyProfile = (data)  => client.put('/members/me', data)
export const changePassword  = (data)  => client.put('/members/me/password', data)
// 회원 탈퇴 — LOCAL 은 password 필수, KAKAO 는 생략 가능. reason 은 선택
// DELETE 에 body 를 실으므로 Content-Type 을 명시한다 (axios 는 method 에 따라 안 붙이는 경우가 있다)
export const withdrawMe      = (data)  =>
  client.delete('/members/me', { data, headers: { 'Content-Type': 'application/json' } })

// 어드민 회원 관리 CRUD
export const getMembers   = (keyword = '', role = '') =>
  client.get('/admin/members', { params: { keyword, ...(role ? { role } : {}) } })
export const getMember    = (memberId) => client.get(`/admin/members/${memberId}`)
export const createMember = (data)     => client.post('/admin/members', data)
export const updateMember = (memberId, data) => client.put(`/admin/members/${memberId}`, data)
export const resetPassword  = (memberId) => client.patch(`/admin/members/${memberId}/reset-password`)
