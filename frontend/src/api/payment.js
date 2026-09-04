import client from './client'

export const preparePayment      = (data)    => client.post('/payments/prepare', data)
export const confirmPayment      = (data)    => client.post('/payments/confirm', data)
export const cancelPendingPayment = (data)   => client.post('/payments/cancel-pending', data)
export const getPaymentByOrder   = (orderId) => client.get(`/payments/orders/${orderId}`)
