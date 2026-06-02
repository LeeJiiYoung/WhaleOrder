import client from './client'

export const getStocks              = (storeId)               => client.get(`/admin/stores/${storeId}/stocks`)
export const setStock               = (storeId, menuId, data) => client.put(`/admin/stores/${storeId}/stocks/${menuId}`, data)
export const getStockRestoreFailures = ()                     => client.get('/admin/stock-restore-failures')
