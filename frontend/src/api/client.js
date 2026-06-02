import axios from 'axios'

const client = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
})

// 요청마다 토큰 자동 첨부
client.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 401 응답 시 Refresh Token으로 자동 갱신 후 원래 요청 재시도 (RTR)
let isRefreshing = false
let failedQueue = []

const processQueue = (error, token = null) => {
  failedQueue.forEach((p) => (error ? p.reject(error) : p.resolve(token)))
  failedQueue = []
}

const logout = () => {
  localStorage.clear()
  window.location.href = '/login'
}

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config

    if (error.response?.status !== 401 || original._retry) {
      return Promise.reject(error)
    }

    const refreshToken = localStorage.getItem('refreshToken')
    if (!refreshToken) {
      logout()
      return Promise.reject(error)
    }

    // 동시에 여러 요청이 401을 받았을 때 refresh를 한 번만 호출
    if (isRefreshing) {
      return new Promise((resolve, reject) => {
        failedQueue.push({ resolve, reject })
      }).then((token) => {
        original.headers.Authorization = `Bearer ${token}`
        return client(original)
      })
    }

    original._retry = true
    isRefreshing = true

    try {
      const res = await client.post('/auth/refresh', { refreshToken })
      const { accessToken, refreshToken: newRefreshToken } = res.data.data
      localStorage.setItem('accessToken', accessToken)
      localStorage.setItem('refreshToken', newRefreshToken)
      client.defaults.headers.common.Authorization = `Bearer ${accessToken}`
      processQueue(null, accessToken)
      original.headers.Authorization = `Bearer ${accessToken}`
      return client(original)
    } catch (e) {
      processQueue(e, null)
      logout()
      return Promise.reject(e)
    } finally {
      isRefreshing = false
    }
  }
)

export default client
