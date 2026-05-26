import client from './client'

export const searchOwners = (keyword = '') =>
  client.get('/admin/members/owners', { params: { keyword } })

// 내 정보 (고객 본인)
export const getMyProfile    = ()      => client.get('/members/me')
export const updateMyProfile = (data)  => client.put('/members/me', data)
export const changePassword  = (data)  => client.put('/members/me/password', data)

// 어드민 회원 관리 CRUD
export const getMembers   = (keyword = '', role = '') =>
  client.get('/admin/members', { params: { keyword, ...(role ? { role } : {}) } })
export const getMember    = (memberId) => client.get(`/admin/members/${memberId}`)
export const createMember = (data)     => client.post('/admin/members', data)
export const updateMember = (memberId, data) => client.put(`/admin/members/${memberId}`, data)
export const deleteMember = (memberId) => client.delete(`/admin/members/${memberId}`)
