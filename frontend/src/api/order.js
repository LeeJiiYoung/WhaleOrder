import client from './client'

export const getMyOrders  = ()          => client.get('/orders')
export const getOrder     = (orderId)   => client.get(`/orders/${orderId}`)
export const cancelOrder  = (orderId)   => client.delete(`/orders/${orderId}`)

// 어드민
export const getAdminOrders = (statuses) => {
  if (!statuses || statuses.length === 0) return client.get('/admin/orders')
  const params = new URLSearchParams()
  statuses.forEach((s) => params.append('statuses', s))
  return client.get(`/admin/orders?${params.toString()}`)
}
export const changeOrderStatus = (orderId, action) => client.patch(`/admin/orders/${orderId}/${action}`)
