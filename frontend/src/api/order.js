import client from './client'

// 내 주문 목록 (커서 페이징) — 응답 data: { content, nextCursor, hasNext }
// cursor 를 생략하면 첫 페이지, 이후엔 직전 응답의 nextCursor 를 그대로 넘긴다.
export const getMyOrders = (cursor, size = 20) => {
  const params = new URLSearchParams({ size })
  if (cursor != null) params.append('cursor', cursor)
  return client.get(`/orders?${params.toString()}`)
}

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
