import client from './client'

export const createOrder      = (data, idempotencyKey) =>
  client.post('/orders', data, { headers: { 'Idempotency-Key': idempotencyKey } })
export const getMyOrders      = ()          => client.get('/orders')
export const getOrder         = (orderId)   => client.get(`/orders/${orderId}`)
export const cancelOrder      = (orderId)   => client.delete(`/orders/${orderId}`)
export const getQueuePosition = (orderId)   => client.get(`/orders/${orderId}/queue-position`)

// 어드민
export const getAdminOrders    = (status)           => client.get('/admin/orders', { params: status ? { status } : {} })
export const changeOrderStatus = (orderId, action)  => client.patch(`/admin/orders/${orderId}/${action}`)

// SSE: 초기 처리 결과 구독 (재고 차감 완료/실패)
export const subscribeOrderResult = (orderId, onResult) =>
  sseStream(`/api/orders/${orderId}/result`, onResult)

// SSE: 어드민 상태 변경 구독 (수락/제조/완료 알림)
export const subscribeOrderUpdates = (orderId, onUpdate) =>
  sseStream(`/api/orders/${orderId}/updates`, onUpdate)

// fetch 기반 SSE 스트리밍 (Authorization 헤더 지원)
function sseStream(url, onEvent) {
  const token = localStorage.getItem('accessToken')
  const controller = new AbortController()

  fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal: controller.signal,
  }).then(async (res) => {
    if (!res.ok || !res.body) return
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const blocks = buffer.split(/\n\n|\r\n\r\n/)
      buffer = blocks.pop() ?? ''
      for (const block of blocks) {
        const dataLine = block.split(/\n|\r\n/).find(l => l.startsWith('data:'))
        if (dataLine) {
          try { onEvent(JSON.parse(dataLine.slice(5).trim())) } catch {}
        }
      }
    }
  }).catch(() => {})

  return () => controller.abort()
}
