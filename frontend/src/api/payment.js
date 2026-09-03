import client from './client'

export const processPayment      = (data)    => client.post('/payments', data)
export const preparePayment      = (data)    => client.post('/payments/prepare', data)
export const confirmPayment      = (data)    => client.post('/payments/confirm', data)
export const getPaymentByOrder   = (orderId) => client.get(`/payments/orders/${orderId}`)
