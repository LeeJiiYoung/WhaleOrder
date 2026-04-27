import client from './client'

export const signUp = (data) => client.post('/auth/signup', data)
export const login = (data) => client.post('/auth/login', data)
