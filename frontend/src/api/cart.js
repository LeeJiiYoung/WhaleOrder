import client from './client'

export const getCart       = ()                          => client.get('/cart')
// force=true 로 호출하면 기존 카트가 다른 매장 메뉴를 가지고 있어도 비우고 새 메뉴를 담는다.
// force 생략(=false) 시 매장 충돌 응답은 412 Precondition Failed.
export const addToCart     = (data, { force = false } = {}) =>
  client.post('/cart/items', data, { params: force ? { force: true } : {} })
export const updateQuantity = (itemKey, quantity)        => client.patch(`/cart/items/${encodeURIComponent(itemKey)}`, null, { params: { quantity } })
export const removeFromCart = (itemKey)                  => client.delete(`/cart/items/${encodeURIComponent(itemKey)}`)
export const clearCart     = ()                          => client.delete('/cart')
