import { useState, useEffect, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getStore, updateStore, openStore, closeStore } from '../../api/store'
import AdminLayout from '../../components/admin/AdminLayout'
import Breadcrumb from '../../components/admin/Breadcrumb'
import styles from './StoreDetailPage.module.css'

const KAKAO_MAP_KEY = import.meta.env.VITE_KAKAO_MAP_KEY

/**
 * 관리자 매장 상세·수정 페이지. (@route /admin/stores/:storeId)
 *
 * - 조회 모드: 매장 정보(기본·주소·영업 시간·좌표) 표시, 영업 시작/종료 토글
 * - 수정 모드: "수정" 버튼 클릭 시 인라인 편집 폼으로 전환
 *   - Daum 우편번호 API로 주소 검색
 *   - 주소 변경 시 Kakao Maps Geocoder로 위도·경도 자동 갱신
 * - 재고 관리 버튼으로 /admin/stores/:storeId/stocks로 이동
 */
export default function StoreDetailPage() {
  const { storeId } = useParams()
  const navigate = useNavigate()
  const [store, setStore] = useState(null)
  const [loading, setLoading] = useState(true)
  const [toggling, setToggling] = useState(false)
  const [error, setError] = useState('')

  const [editMode, setEditMode] = useState(false)
  const [form, setForm] = useState(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState('')
  const [sdkReady, setSdkReady] = useState(false)

  const mapRef          = useRef(null)
  const mapInstanceRef  = useRef(null)
  const markerRef       = useRef(null)
  const mapInitialized  = useRef(false)

  const load = () => {
    setLoading(true)
    getStore(storeId)
      .then((res) => {
        const s = res.data.data
        setStore(s)
        setForm(storeToForm(s))
      })
      .catch(() => setError('매장 정보를 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [storeId])

  // Kakao Maps SDK — onload 후 maps.load()까지 완료해야 API 사용 가능
  useEffect(() => {
    if (window.kakao?.maps) {
      window.kakao.maps.load(() => setSdkReady(true))
      return
    }
    const script = document.createElement('script')
    script.src = `//dapi.kakao.com/v2/maps/sdk.js?appkey=${KAKAO_MAP_KEY}`
    script.async = true
    script.onload = () => window.kakao.maps.load(() => setSdkReady(true))
    document.head.appendChild(script)
    return () => { if (document.head.contains(script)) document.head.removeChild(script) }
  }, [])

  // Daum 우편번호 SDK
  useEffect(() => {
    if (!editMode) return
    if (window.daum?.Postcode) return
    const script = document.createElement('script')
    script.src = 'https://t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js'
    script.async = true
    document.head.appendChild(script)
    return () => { if (document.head.contains(script)) document.head.removeChild(script) }
  }, [editMode])

  const storeToForm = (s) => ({
    name:          s.name,
    postalCode:    s.postalCode,
    address:       s.address,
    addressDetail: s.addressDetail ?? '',
    phone:         s.phone ?? '',
    openTime:      s.openTime,
    closeTime:     s.closeTime,
    latitude:      s.latitude ?? null,
    longitude:     s.longitude ?? null,
  })

  // 위치 선택 지도 초기화 (최초 1회)
  const initPickerMap = (lat, lng) => {
    if (!mapRef.current || !window.kakao?.maps) return
    const kakao = window.kakao
    const pos = new kakao.maps.LatLng(lat, lng)
    const map = new kakao.maps.Map(mapRef.current, { center: pos, level: 4 })
    const marker = new kakao.maps.Marker({ map, position: pos, draggable: true })

    kakao.maps.event.addListener(marker, 'dragend', () => {
      const p = marker.getPosition()
      setForm((prev) => ({ ...prev, latitude: p.getLat(), longitude: p.getLng() }))
    })
    kakao.maps.event.addListener(map, 'click', ({ latLng }) => {
      marker.setPosition(latLng)
      setForm((prev) => ({ ...prev, latitude: latLng.getLat(), longitude: latLng.getLng() }))
    })

    mapInstanceRef.current = map
    markerRef.current      = marker
    mapInitialized.current = true
  }

  const geocodeAddress = (address) => {
    const run = () => {
      const geocoder = new window.kakao.maps.services.Geocoder()
      geocoder.addressSearch(address, (result, status) => {
        if (status !== window.kakao.maps.services.Status.OK) return
        const lat = parseFloat(result[0].y)
        const lng = parseFloat(result[0].x)
        setForm((prev) => ({ ...prev, latitude: lat, longitude: lng }))
        if (mapInitialized.current) {
          mapInstanceRef.current.panTo(new window.kakao.maps.LatLng(lat, lng))
          markerRef.current.setPosition(new window.kakao.maps.LatLng(lat, lng))
        } else if (mapRef.current) {
          initPickerMap(lat, lng)
        }
      })
    }
    if (window.kakao?.maps) window.kakao.maps.load(run)
    else setTimeout(() => { if (window.kakao?.maps) window.kakao.maps.load(run) }, 500)
  }

  // SDK 준비 + 수정 모드 진입 시 지도 초기화
  useEffect(() => {
    if (!sdkReady || !editMode || !mapRef.current || mapInitialized.current) return
    const lat = form?.latitude ?? 37.5665
    const lng = form?.longitude ?? 126.978
    initPickerMap(lat, lng)
  }, [sdkReady, editMode])

  const openPostcode = () => {
    new window.daum.Postcode({
      oncomplete(data) {
        setForm((prev) => ({
          ...prev,
          postalCode: data.zonecode,
          address:    data.address,
          latitude:   null,
          longitude:  null,
        }))
        geocodeAddress(data.address)
      },
    }).open()
  }

  const handleChange = (e) => {
    const { name, value } = e.target
    setForm((prev) => ({ ...prev, [name]: value }))
    setSaveError('')
  }

  const resetMap = () => {
    mapInstanceRef.current = null
    markerRef.current      = null
    mapInitialized.current = false
  }

  const handleSave = async () => {
    setSaving(true)
    setSaveError('')
    try {
      await updateStore(storeId, form)
      await load()
      setEditMode(false)
      resetMap()
    } catch (err) {
      setSaveError(err.response?.data?.message || '저장 중 오류가 발생했습니다')
    } finally {
      setSaving(false)
    }
  }

  const handleCancel = () => {
    setForm(storeToForm(store))
    setSaveError('')
    setEditMode(false)
    resetMap()
  }

  const handleToggle = async () => {
    setToggling(true)
    try {
      store.status === 'OPEN' ? await closeStore(storeId) : await openStore(storeId)
      load()
    } catch {
      alert('상태 변경 중 오류가 발생했습니다')
    } finally {
      setToggling(false)
    }
  }

  return (
    <AdminLayout>
      <Breadcrumb items={[{ label: '매장 관리' }, { label: '매장 목록', path: '/admin/stores' }, { label: store?.name ?? '매장 상세' }]} />
      <div className={styles.header}>
        <button className={styles.backBtn} onClick={() => navigate('/admin/stores')}>← 목록으로</button>
        <h1 className={styles.pageTitle}>{store?.name ?? '매장 상세'}</h1>

        {store && !editMode && (
          <>
            <button
              className={styles.stockBtn}
              onClick={() => navigate(`/admin/stores/${storeId}/stocks`)}
            >
              재고 관리
            </button>
            <button
              className={`${styles.toggleBtn} ${store.status === 'OPEN' ? styles.closeBtn : styles.openBtn}`}
              onClick={handleToggle}
              disabled={toggling}
            >
              {toggling ? '변경 중...' : store.status === 'OPEN' ? '영업 종료' : '영업 시작'}
            </button>
          </>
        )}

        {editMode && (
          <>
            <button className={styles.saveBtn} onClick={handleSave} disabled={saving}>
              {saving ? '저장 중...' : '저장'}
            </button>
            <button className={styles.cancelBtn} onClick={handleCancel} disabled={saving}>취소</button>
          </>
        )}
      </div>

      {loading && <p className={styles.loading}>불러오는 중...</p>}
      {error   && <p className={styles.error}>{error}</p>}

      {store && !editMode && (
        <div className={styles.card}>
          <p className={styles.sectionTitle}>기본 정보</p>
          <div className={styles.grid}>
            <Field label="매장명"   value={store.name} />
            <Field label="전화번호" value={store.phone || '-'} />
            <Field label="상태" value={
              <span className={`${styles.badge} ${store.status === 'OPEN' ? styles.badgeOpen : styles.badgeClosed}`}>
                {store.status === 'OPEN' ? '영업 중' : '마감'}
              </span>
            } />
            <Field label="점주명" value={store.ownerName} />
          </div>

          <p className={styles.sectionTitle}>주소 정보</p>
          <div className={styles.grid}>
            <Field label="우편번호"    value={store.postalCode} />
            <Field label="도로명 주소" value={store.address} />
            <Field label="상세 주소"   value={store.addressDetail || '-'} />
            <Field label="좌표" value={
              store.latitude
                ? `${store.latitude.toFixed(5)}, ${store.longitude.toFixed(5)}`
                : '미등록'
            } />
          </div>

          <p className={styles.sectionTitle}>영업 시간</p>
          <div className={styles.grid}>
            <Field label="영업 시작" value={store.openTime} />
            <Field label="영업 종료" value={store.closeTime} />
          </div>
          <div className={styles.cardActions}>
            <button className={styles.editBtn} onClick={() => setEditMode(true)}>수정</button>
          </div>
        </div>
      )}

      {form && editMode && (
        <div className={styles.card}>
          {saveError && <p className={styles.saveError}>{saveError}</p>}

          <p className={styles.sectionTitle}>기본 정보</p>
          <div className={styles.formGrid}>
            <EditField label="매장명" name="name" value={form.name} onChange={handleChange} required styles={styles} />
            <EditField label="전화번호" name="phone" value={form.phone} onChange={handleChange} styles={styles} />
          </div>

          <p className={styles.sectionTitle}>주소 정보</p>
          <div className={styles.field}>
            <label className={styles.editLabel}>우편번호 <span className={styles.required}>*</span></label>
            <div className={styles.addrRow}>
              <input className={styles.input} value={form.postalCode} readOnly placeholder="주소 검색을 눌러주세요" />
              <button type="button" className={styles.searchBtn} onClick={openPostcode}>🔍 주소 검색</button>
            </div>
          </div>
          <div className={styles.field}>
            <label className={styles.editLabel}>도로명 주소 <span className={styles.required}>*</span></label>
            <div className={styles.addrRow}>
              <input className={styles.input} value={form.address} readOnly />
              {form.latitude && <span className={styles.coordBadge}>📍 좌표 저장됨</span>}
            </div>
          </div>
          <EditField label="상세 주소" name="addressDetail" value={form.addressDetail} onChange={handleChange} styles={styles} />

          <p className={styles.sectionTitle}>위치 선택</p>
          <p className={styles.mapPickerHint}>
            마커를 드래그하거나 지도를 클릭해 정확한 위치를 조정하세요
          </p>
          <div className={styles.mapPickerWrapper}>
            <div ref={mapRef} className={styles.mapPicker} />
            {!form.latitude && (
              <div className={styles.mapOverlay}>🗺️ 주소를 검색하면 지도가 이동합니다</div>
            )}
          </div>
          {form.latitude && (
            <p className={styles.coordDisplay}>
              📍 {form.latitude.toFixed(6)}, {form.longitude.toFixed(6)}
            </p>
          )}

          <p className={styles.sectionTitle}>영업 시간</p>
          <div className={styles.formGrid}>
            <EditField label="영업 시작" name="openTime" type="time" value={form.openTime} onChange={handleChange} required styles={styles} />
            <EditField label="영업 종료" name="closeTime" type="time" value={form.closeTime} onChange={handleChange} required styles={styles} />
          </div>
        </div>
      )}
    </AdminLayout>
  )
}

function Field({ label, value }) {
  return (
    <div className={styles.item}>
      <span className={styles.label}>{label}</span>
      <span className={styles.value}>{value}</span>
    </div>
  )
}

function EditField({ label, name, type = 'text', value, onChange, required, styles }) {
  return (
    <div className={styles.field}>
      <label className={styles.editLabel}>
        {label}{required && <span className={styles.required}> *</span>}
      </label>
      <input
        className={styles.input}
        name={name}
        type={type}
        value={value}
        onChange={onChange}
        autoComplete="off"
      />
    </div>
  )
}