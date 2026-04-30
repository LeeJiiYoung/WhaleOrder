import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import SignUpPage from './pages/SignUpPage'
import LoginPage from './pages/LoginPage'
import HomePage from './pages/HomePage'
import StoreCreatePage from './pages/admin/StoreCreatePage'
import StoreListPage from './pages/admin/StoreListPage'
import StoreDetailPage from './pages/admin/StoreDetailPage'
import AdminLayout from './components/admin/AdminLayout'

function PrivateRoute({ children }) {
  const token = localStorage.getItem('accessToken')
  return token ? children : <Navigate to="/login" replace />
}

// ADMIN 역할만 접근 가능
function AdminRoute({ children }) {
  const token = localStorage.getItem('accessToken')
  const role = localStorage.getItem('role')
  if (!token) return <Navigate to="/login" replace />
  if (role !== 'ADMIN') return <Navigate to="/" replace />
  return children
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/signup" element={<SignUpPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/"
          element={
            <PrivateRoute>
              <HomePage />
            </PrivateRoute>
          }
        />
        <Route
          path="/admin/store-create"
          element={
            <AdminRoute>
              <StoreCreatePage />
            </AdminRoute>
          }
        />
        <Route path="/admin/stores" element={<AdminRoute><StoreListPage /></AdminRoute>} />
        <Route path="/admin/stores/:storeId" element={<AdminRoute><StoreDetailPage /></AdminRoute>} />
        {/* 아직 구현 전인 어드민 페이지 */}
        {['/admin/menus', '/admin/orders', '/admin/members'].map((path) => (
          <Route
            key={path}
            path={path}
            element={
              <AdminRoute>
                <AdminLayout>
                  <p style={{ color: '#999', marginTop: 40, textAlign: 'center' }}>준비 중입니다.</p>
                </AdminLayout>
              </AdminRoute>
            }
          />
        ))}
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
