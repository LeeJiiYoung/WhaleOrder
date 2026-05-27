import client from './client'

export const processPayment      = (data)    => client.post('/payments', data)
export const getPaymentByOrder   = (orderId) => client.get(`/payments/orders/${orderId}`)
