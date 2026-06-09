import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import SignUpPage from './pages/SignUpPage'
import LoginPage from './pages/LoginPage'
import OAuth2CallbackPage from './pages/OAuth2CallbackPage'
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
import EventListPage from './pages/customer/EventListPage'
import EventDetailPage from './pages/customer/EventDetailPage'
import AdminOrderPage from './pages/admin/AdminOrderPage'
import StockPage from './pages/admin/StockPage'
import StockRestoreFailurePage from './pages/admin/StockRestoreFailurePage'
import EventManagePage from './pages/admin/EventManagePage'
import GoodsManagePage from './pages/admin/GoodsManagePage'
import AdminMemberPage from './pages/admin/AdminMemberPage'
import MyProfilePage from './pages/customer/MyProfilePage'
import PaymentPage from './pages/customer/PaymentPage'

/**
 * 로그인한 사용자만 접근 가능한 라우트 가드.
 * accessToken 없으면 /login으로 리다이렉트.
 * @param {{ children: React.ReactNode }} props
 */
function PrivateRoute({ children }) {
  const token = localStorage.getItem('accessToken')
  return token ? children : <Navigate to="/login" replace />
}

/**
 * ADMIN 역할만 접근 가능한 라우트 가드.
 * 미로그인 시 /login, 비관리자 시 / 로 리다이렉트.
 * @param {{ children: React.ReactNode }} props
 */
function AdminRoute({ children }) {
  const token = localStorage.getItem('accessToken')
  const role = localStorage.getItem('role')
  if (!token) return <Navigate to="/login" replace />
  if (role !== 'ADMIN') return <Navigate to="/" replace />
  return children
}

/**
 * 앱 루트 컴포넌트. 전체 라우팅 구조를 정의한다.
 *
 * - 공개 경로: /login, /signup, /oauth2/callback
 * - 고객 경로 (PrivateRoute): /stores, /menus, /cart, /payment, /orders/:id, /events 등
 * - 관리자 경로 (AdminRoute): /admin/** (매장·메뉴·주문·재고·한정판매·회원 관리)
 */
export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/signup" element={<SignUpPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/oauth2/callback" element={<OAuth2CallbackPage />} />
        <Route path="/" element={<Navigate to="/stores" replace />} />

        {/* 고객 */}
        <Route path="/stores" element={<PrivateRoute><StoreSelectPage /></PrivateRoute>} />
        <Route path="/menus" element={<PrivateRoute><CustomerMenuListPage /></PrivateRoute>} />
        <Route path="/menus/:menuId" element={<PrivateRoute><CustomerMenuDetailPage /></PrivateRoute>} />
        <Route path="/cart" element={<PrivateRoute><CartPage /></PrivateRoute>} />
        <Route path="/payment" element={<PrivateRoute><PaymentPage /></PrivateRoute>} />
        <Route path="/orders/:orderId" element={<PrivateRoute><OrderDetailPage /></PrivateRoute>} />
        <Route path="/my-orders" element={<PrivateRoute><MyOrdersPage /></PrivateRoute>} />
        <Route path="/profile"   element={<PrivateRoute><MyProfilePage /></PrivateRoute>} />
        <Route path="/events" element={<PrivateRoute><EventListPage /></PrivateRoute>} />
        <Route path="/events/:eventId" element={<PrivateRoute><EventDetailPage /></PrivateRoute>} />

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
        <Route path="/admin/stock-restore-failures" element={<AdminRoute><StockRestoreFailurePage /></AdminRoute>} />

        {/* 한정판매 */}
        <Route path="/admin/events" element={<AdminRoute><EventManagePage /></AdminRoute>} />
        <Route path="/admin/goods"  element={<AdminRoute><GoodsManagePage /></AdminRoute>} />

        {/* 회원 관리 */}
        <Route path="/admin/members" element={<AdminRoute><AdminMemberPage /></AdminRoute>} />

        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
