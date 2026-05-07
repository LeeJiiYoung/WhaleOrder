import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import SignUpPage from './pages/SignUpPage'
import LoginPage from './pages/LoginPage'
import StoreCreatePage from './pages/admin/StoreCreatePage'
import StoreListPage from './pages/admin/StoreListPage'
import StoreDetailPage from './pages/admin/StoreDetailPage'
import MenuListPage from './pages/admin/MenuListPage'
import MenuCreatePage from './pages/admin/MenuCreatePage'
import MenuDetailPage from './pages/admin/MenuDetailPage'
import AdminLayout from './components/admin/AdminLayout'
import StoreSelectPage from './pages/customer/StoreSelectPage'
import CustomerMenuListPage from './pages/customer/CustomerMenuListPage'
import CustomerMenuDetailPage from './pages/customer/CustomerMenuDetailPage'
import CartPage from './pages/customer/CartPage'
import OrderDetailPage from './pages/customer/OrderDetailPage'
import MyOrdersPage from './pages/customer/MyOrdersPage'
import AdminOrderPage from './pages/admin/AdminOrderPage'
import StockPage from './pages/admin/StockPage'

function PrivateRoute({ children }) {
  const token = localStorage.getItem('accessToken')
  return token ? children : <Navigate to="/login" replace />
}

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
        <Route path="/" element={<Navigate to="/stores" replace />} />

        {/* 고객 */}
        <Route path="/stores" element={<PrivateRoute><StoreSelectPage /></PrivateRoute>} />
        <Route path="/menus" element={<PrivateRoute><CustomerMenuListPage /></PrivateRoute>} />
        <Route path="/menus/:menuId" element={<PrivateRoute><CustomerMenuDetailPage /></PrivateRoute>} />
        <Route path="/cart" element={<PrivateRoute><CartPage /></PrivateRoute>} />
        <Route path="/orders/:orderId" element={<PrivateRoute><OrderDetailPage /></PrivateRoute>} />
        <Route path="/my-orders" element={<PrivateRoute><MyOrdersPage /></PrivateRoute>} />

        {/* 매장 */}
        <Route path="/admin/store-create" element={<AdminRoute><StoreCreatePage /></AdminRoute>} />
        <Route path="/admin/stores" element={<AdminRoute><StoreListPage /></AdminRoute>} />
        <Route path="/admin/stores/:storeId" element={<AdminRoute><StoreDetailPage /></AdminRoute>} />

        {/* 메뉴 */}
        <Route path="/admin/menus" element={<AdminRoute><MenuListPage /></AdminRoute>} />
        <Route path="/admin/menu-create" element={<AdminRoute><MenuCreatePage /></AdminRoute>} />
        <Route path="/admin/menus/:menuId" element={<AdminRoute><MenuDetailPage /></AdminRoute>} />

        {/* 주문 */}
        <Route path="/admin/orders" element={<AdminRoute><AdminOrderPage /></AdminRoute>} />

        {/* 재고 */}
        <Route path="/admin/stores/:storeId/stocks" element={<AdminRoute><StockPage /></AdminRoute>} />

        {/* 미구현 */}
        {['/admin/members'].map((path) => (
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
