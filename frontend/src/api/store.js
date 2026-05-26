import client from './client'

// 어드민
export const createStore  = (data) => client.post('/admin/stores', data)
export const getStores    = ()     => client.get('/admin/stores')
export const getStore     = (id)   => client.get(`/admin/stores/${id}`)

export const openStore  = (id) => client.patch(`/admin/stores/${id}/open`)
export const closeStore = (id) => client.patch(`/admin/stores/${id}/close`)

// 고객
export const getOpenStores       = ()   => client.get('/stores')
export const getStoreForCustomer = (id) => client.get(`/stores/${id}`)
