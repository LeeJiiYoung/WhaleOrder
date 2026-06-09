import { useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { getMyProfile } from '../api/member'

/**
 * 카카오 OAuth2 로그인 콜백 처리 페이지. (@route /oauth2/callback)
 *
 * - URL 쿼리 파라미터에서 accessToken·refreshToken·role을 추출해 localStorage에 저장
 * - 카카오 닉네임은 URL에 포함되지 않으므로 /api/members/me로 별도 조회
 * - React StrictMode의 이중 실행을 useRef로 방지
 * - 처리 완료 후 ADMIN은 /admin/store-create, 고객은 /stores로 이동
 */
export default function OAuth2CallbackPage() {
  const navigate = useNavigate()
  // StrictMode에서 useEffect가 두 번 실행될 때 중복 처리 방지
  const processed = useRef(false)
  // navigate 호출 전 URL 파라미터를 컴포넌트 생성 시점에 캡처
  const params = useRef(new URLSearchParams(window.location.search))

  useEffect(() => {
    if (processed.current) return
    processed.current = true

    const accessToken = params.current.get('accessToken')
    const refreshToken = params.current.get('refreshToken')
    const role = params.current.get('role')

    if (!accessToken) {
      navigate('/login', { replace: true })
      return
    }

    localStorage.setItem('accessToken', accessToken)
    if (refreshToken) localStorage.setItem('refreshToken', refreshToken)
    if (role) localStorage.setItem('role', role)

    // 카카오 닉네임 조회 (URL에 포함 불가 → API로 가져옴)
    getMyProfile()
      .then(res => {
        const nickname = res.data.data?.nickname || res.data.data?.name
        if (nickname) localStorage.setItem('nickname', nickname)
      })
      .catch(() => {})
      .finally(() => {
        if (role === 'ADMIN') {
          navigate('/admin/store-create', { replace: true })
        } else {
          navigate('/stores', { replace: true })
        }
      })
  }, [navigate])

  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh' }}>
      <p>카카오 로그인 처리 중...</p>
    </div>
  )
}