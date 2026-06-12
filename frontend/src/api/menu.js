import client from './client'

// 고객 — 매장별 메뉴 + 실시간 재고 통합 조회
export const getCustomerMenus = (storeId, category) =>
  client.get(`/stores/${storeId}/menus`, { params: category ? { category } : {} })

// 어드민
export const getMenus = (category) =>
  client.get('/admin/menus', { params: category ? { category } : {} })

export const getMenu = (menuId) => client.get(`/admin/menus/${menuId}`)

export const createMenu = (formData) =>
  client.post('/admin/menus', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })

export const updateMenu = (menuId, formData) =>
  client.put(`/admin/menus/${menuId}`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })

export const deactivateMenu = (menuId) => client.delete(`/admin/menus/${menuId}`)
export const activateMenu   = (menuId) => client.patch(`/admin/menus/${menuId}/activate`)

export const addOption    = (menuId, data)            => client.post(`/admin/menus/${menuId}/options`, data)
export const updateOption = (menuId, optionId, data)  => client.put(`/admin/menus/${menuId}/options/${optionId}`, data)
export const deleteOption = (menuId, optionId)        => client.delete(`/admin/menus/${menuId}/options/${optionId}`)
