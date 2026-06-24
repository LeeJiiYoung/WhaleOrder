import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { getOpenStores } from '../../api/store'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './StoreSelectPage.module.css'

const KAKAO_MAP_KEY = import.meta.env.VITE_KAKAO_MAP_KEY

// 하버사인 공식: 두 좌표 사이의 거리(km) 계산
function haversineDistance(lat1, lon1, lat2, lon2) {
  const R = 6371
  const dLat = ((lat2 - lat1) * Math.PI) / 180
  const dLon = ((lon2 - lon1) * Math.PI) / 180
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLon / 2) ** 2
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

function formatDistance(km) {
  if (km < 1) return `${Math.round(km * 1000)}m`
  return `${km.toFixed(1)}km`
}

/**
 * 고객 매장 선택 페이지. (@route /stores)
 *
 * - 카카오 지도에 영업 중인 매장 마커 표시, 현재 위치 파란 점 표시
 * - 마커 클릭 시 인포윈도우: 매장명·주소·영업 시간·현재 위치까지의 거리
 * - 하단 카드 목록: 하버사인 공식으로 현재 위치 기준 가까운 순 정렬
 * - 카드 우측 뱃지: 1km 미만은 "350m", 이상은 "1.2km" 형식
 * - 좌표 없는 매장은 지도에 미표시, 목록 맨 뒤에 표시
 * - 매장 선택 시 storeId·storeName을 localStorage에 저장 후 /menus로 이동
 */
export default function StoreSelectPage() {
  const navigate = useNavigate()
  const [stores, setStores] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [mapReady, setMapReady] = useState(false)
  const [userLocation, setUserLocation] = useState(null) // { lat, lng }
  const mapRef = useRef(null)
  const mapInstanceRef = useRef(null)

  // 매장 목록 로드
  useEffect(() => {
    getOpenStores()
      .then((res) => setStores(res.data.data))
      .catch(() => setError('매장 목록을 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [])

  // 현재 위치 획득
  useEffect(() => {
    if (!navigator.geolocation) return
    navigator.geolocation.getCurrentPosition(
      (pos) => setUserLocation({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
      () => {},
      { timeout: 5000 }
    )
  }, [])

  // 카카오 지도 SDK 로드
  useEffect(() => {
    if (window.kakao?.maps) {
      setMapReady(true)
      return
    }
    const script = document.createElement('script')
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${KAKAO_MAP_KEY}&autoload=false`
    script.async = true
    script.onload = () => window.kakao.maps.load(() => setMapReady(true))
    document.head.appendChild(script)
    return () => {
      if (document.head.contains(script)) document.head.removeChild(script)
    }
  }, [])

  // 지도 초기화 및 마커 렌더링
  useEffect(() => {
    if (!mapReady || !mapRef.current || stores.length === 0) return

    const kakao = window.kakao
    const storesWithCoords = stores.filter((s) => s.latitude && s.longitude)

    const defaultCenter = userLocation
      ? new kakao.maps.LatLng(userLocation.lat, userLocation.lng)
      : storesWithCoords.length > 0
        ? new kakao.maps.LatLng(storesWithCoords[0].latitude, storesWithCoords[0].longitude)
        : new kakao.maps.LatLng(37.5665, 126.978)

    const map = new kakao.maps.Map(mapRef.current, { center: defaultCenter, level: 5 })
    mapInstanceRef.current = map

    // 현재 위치 마커 (파란 원)
    if (userLocation) {
      const myMarkerContent = `
        <div style="
          width:16px;height:16px;
          background:#4f46e5;border:3px solid #fff;
          border-radius:50%;box-shadow:0 2px 6px rgba(0,0,0,0.3)
        "></div>`
      new kakao.maps.CustomOverlay({
        map,
        position: new kakao.maps.LatLng(userLocation.lat, userLocation.lng),
        content: myMarkerContent,
        yAnchor: 0.5,
      })
    }

    // 매장 마커 + 인포윈도우
    storesWithCoords.forEach((store) => {
      const dist = userLocation
        ? formatDistance(haversineDistance(userLocation.lat, userLocation.lng, store.latitude, store.longitude))
        : null

      const marker = new kakao.maps.Marker({
        map,
        position: new kakao.maps.LatLng(store.latitude, store.longitude),
        title: store.name,
      })

      const infoContent = `
        <div style="padding:10px 14px;font-family:-apple-system,sans-serif;min-width:140px;max-width:200px">
          <div style="font-size:13px;font-weight:700;color:#111;margin-bottom:3px">${store.name}</div>
          <div style="font-size:11px;color:#666;margin-bottom:6px;line-height:1.4">${store.address}</div>
          <div style="display:flex;justify-content:space-between;align-items:center">
            <span style="font-size:11px;color:#059669;font-weight:600">${store.openTime} ~ ${store.closeTime}</span>
            ${dist ? `<span style="font-size:11px;color:#4f46e5;font-weight:700">${dist}</span>` : ''}
          </div>
        </div>`
      const infoWindow = new kakao.maps.InfoWindow({ content: infoContent, removable: true })

      kakao.maps.event.addListener(marker, 'click', () => {
        if (map._openInfoWindow) map._openInfoWindow.close()
        infoWindow.open(map, marker)
        map._openInfoWindow = infoWindow
      })
    })

    kakao.maps.event.addListener(map, 'click', () => {
      if (map._openInfoWindow) {
        map._openInfoWindow.close()
        map._openInfoWindow = null
      }
    })
  }, [mapReady, stores, userLocation])

  // 거리 기준 정렬: 좌표 있는 매장은 가까운 순, 좌표 없는 매장은 맨 뒤
  const sortedStores = [...stores].sort((a, b) => {
    const hasA = a.latitude && a.longitude
    const hasB = b.latitude && b.longitude
    if (!hasA && !hasB) return 0
    if (!hasA) return 1
    if (!hasB) return -1
    if (!userLocation) return 0
    const distA = haversineDistance(userLocation.lat, userLocation.lng, a.latitude, a.longitude)
    const distB = haversineDistance(userLocation.lat, userLocation.lng, b.latitude, b.longitude)
    return distA - distB
  })

  // 현재 위치 기준 가장 가까운 매장 (좌표 있는 매장 중 첫 번째). 위치/좌표 정보 없으면 null
  const nearestStore = userLocation
    ? sortedStores.find((s) => s.latitude && s.longitude) ?? null
    : null

  const handleSelect = (store) => {
    if (nearestStore && nearestStore.storeId !== store.storeId) {
      const ok = window.confirm(
        `가장 가까운 매장은 "${nearestStore.name}" 입니다.\n` +
        `"${store.name}" 매장에서 주문하시겠습니까?`
      )
      if (!ok) return
    }
    localStorage.setItem('selectedStoreId', store.storeId)
    localStorage.setItem('selectedStoreName', store.name)
    navigate('/menus')
  }

  const storesWithCoords = stores.filter((s) => s.latitude && s.longitude)
  const storesWithoutCoords = stores.filter((s) => !s.latitude || !s.longitude)

  return (
    <CustomerLayout>
      <div className={styles.header}>
        <h1 className={styles.title}>매장을 선택하세요</h1>
        <p className={styles.subtitle}>
          {userLocation ? '현재 위치 기준 가까운 순으로 표시됩니다' : '지도에서 매장을 찾거나 아래 목록에서 선택하세요'}
        </p>
      </div>

      {error && <div className={styles.errorBox}>{error}</div>}

      {/* 카카오 지도 */}
      {!loading && storesWithCoords.length > 0 && (
        <div className={styles.mapSection}>
          <div ref={mapRef} className={styles.map} />
          <p className={styles.mapHint}>
            {userLocation ? '● 파란 점이 현재 위치입니다 · 마커를 클릭하면 매장 정보를 볼 수 있어요' : '마커를 클릭하면 매장 정보를 볼 수 있어요'}
          </p>
        </div>
      )}

      {loading && <div className={styles.empty}>불러오는 중...</div>}

      {!loading && stores.length === 0 && !error && (
        <div className={styles.empty}>현재 영업 중인 매장이 없습니다</div>
      )}

      {!loading && stores.length > 0 && (
        <>
          <p className={styles.listLabel}>
            {userLocation ? '가까운 매장 순' : '전체 매장 목록'}
          </p>
          <div className={styles.grid}>
            {sortedStores.map((store) => {
              const dist = userLocation && store.latitude && store.longitude
                ? haversineDistance(userLocation.lat, userLocation.lng, store.latitude, store.longitude)
                : null

              return (
                <button
                  key={store.storeId}
                  className={styles.card}
                  onClick={() => handleSelect(store)}
                >
                  <div className={styles.cardIcon}>🏪</div>
                  <div className={styles.cardBody}>
                    <p className={styles.storeName}>{store.name}</p>
                    <p className={styles.storeAddress}>
                      {store.address}
                      {store.addressDetail ? ` ${store.addressDetail}` : ''}
                    </p>
                    <p className={styles.storeHours}>{store.openTime} ~ {store.closeTime}</p>
                  </div>
                  <div className={styles.badgeGroup}>
                    <span className={styles.openBadge}>영업 중</span>
                    {dist !== null && (
                      <span className={styles.distBadge}>{formatDistance(dist)}</span>
                    )}
                  </div>
                </button>
              )
            })}
          </div>

          {storesWithoutCoords.length > 0 && storesWithCoords.length > 0 && (
            <p className={styles.noCoordNote}>
              * 거리 표시가 없는 매장은 위치 정보가 등록되지 않은 매장입니다
            </p>
          )}
        </>
      )}
    </CustomerLayout>
  )
}