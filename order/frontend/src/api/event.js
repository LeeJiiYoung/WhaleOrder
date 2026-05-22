import client from './client'

// 어드민
export const createGoods      = (data)    => client.post('/admin/goods', data)
export const getAdminGoods    = ()        => client.get('/admin/goods')
export const createAdminEvent = (data)    => client.post('/admin/events', data)
export const getAdminEvents   = ()        => client.get('/admin/events')
export const openAdminEvent   = (id)      => client.patch(`/admin/events/${id}/open`)
export const closeAdminEvent  = (id)      => client.patch(`/admin/events/${id}/close`)

// 고객
export const getEvents      = ()          => client.get('/events')
export const getEvent       = (eventId)   => client.get(`/events/${eventId}`)
export const getQueueStatus = (eventId)   => client.get(`/events/${eventId}/queue/status`)
export const joinQueue      = (eventId)   => client.post(`/events/${eventId}/queue/join`)
export const purchaseEvent  = (eventId)   => client.post(`/events/${eventId}/purchase`)

/**
 * SSE 연결 (fetch + ReadableStream 방식 — EventSource는 Authorization 헤더 미지원)
 * 반환값: 연결 종료 함수 (AbortController.abort)
 */
export function subscribeSse(eventId, { onQueue, onReady, onError, onClose } = {}) {
  const controller = new AbortController()
  const token = localStorage.getItem('accessToken')

  ;(async () => {
    try {
      const res = await fetch(`/api/events/${eventId}/queue/sse`, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: controller.signal,
      })
      if (!res.ok || !res.body) throw new Error('SSE 연결 실패')

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })

        // SSE 메시지 구분자 '\n\n'
        const parts = buf.split('\n\n')
        buf = parts.pop()

        for (const part of parts) {
          let eventName = '', data = ''
          for (const line of part.trim().split('\n')) {
            if (line.startsWith('event:')) eventName = line.slice(6).trim()
            if (line.startsWith('data:'))  data      = line.slice(5).trim()
          }
          if (eventName === 'queue'         && data) onQueue?.(JSON.parse(data))
          if (eventName === 'purchaseReady' && data) onReady?.(JSON.parse(data))
        }
      }
      onClose?.()
    } catch (err) {
      if (err.name !== 'AbortError') onError?.(err)
    }
  })()

  return () => controller.abort()
}
