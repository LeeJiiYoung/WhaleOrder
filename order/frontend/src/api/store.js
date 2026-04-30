import client from './client'

export const createStore  = (data) => client.post('/admin/stores', data)
export const getStores    = ()     => client.get('/admin/stores')
export const getStore     = (id)   => client.get(`/admin/stores/${id}`)
