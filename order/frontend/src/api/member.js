import client from './client'

export const searchOwners = (keyword = '') =>
  client.get('/admin/members/owners', { params: { keyword } })
