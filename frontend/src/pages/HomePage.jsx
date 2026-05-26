import { useNavigate } from 'react-router-dom'

export default function HomePage() {
  const navigate = useNavigate()
  const nickname = localStorage.getItem('nickname')

  const handleLogout = () => {
    localStorage.clear()
    navigate('/login')
  }

  return (
    <div style={{ padding: 40, textAlign: 'center' }}>
      <h1>안녕하세요, {nickname ?? '회원'}님!</h1>
      <button onClick={handleLogout} style={{ marginTop: 20, padding: '8px 20px', cursor: 'pointer' }}>
        로그아웃
      </button>
    </div>
  )
}
