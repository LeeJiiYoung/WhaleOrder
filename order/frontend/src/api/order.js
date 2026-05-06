import client from './client'

export const createOrder  = (data)      => client.post('/orders', data)
export const getMyOrders  = ()          => client.get('/orders')
export const getOrder     = (orderId)   => client.get(`/orders/${orderId}`)
export const cancelOrder  = (orderId)   => client.delete(`/orders/${orderId}`)

// 어드민
export const getAdminOrders  = (status) => client.get('/admin/orders', { params: status ? { status } : {} })
export const changeOrderStatus = (orderId, action) => client.patch(`/admin/orders/${orderId}/${action}`)
