import client from './client'

export const getCart       = ()                          => client.get('/cart')
export const addToCart     = (data)                      => client.post('/cart/items', data)
export const updateQuantity = (itemKey, quantity)        => client.patch(`/cart/items/${encodeURIComponent(itemKey)}`, null, { params: { quantity } })
export const removeFromCart = (itemKey)                  => client.delete(`/cart/items/${encodeURIComponent(itemKey)}`)
export const clearCart     = ()                          => client.delete('/cart')
