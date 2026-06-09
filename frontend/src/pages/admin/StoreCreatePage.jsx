import { useState, useEffect, useRef, useCallback } from 'react'
import { createStore } from '../../api/store'
import AdminLayout from '../../components/admin/AdminLayout'
import OwnerSearchPopup from '../../components/admin/OwnerSearchPopup'
import styles from './StoreCreatePage.module.css'

const KAKAO_MAP_KEY = import.meta.env.VITE_KAKAO_MAP_KEY

// ── 모듈 레벨 SDK 로더 ──────────────────────────────────────────────────────
// StrictMode 이중 마운트에 관계없이 SDK를 한 번만 로드하고,
// 준비되면 등록된 콜백을 모두 즉시 호출한다.
let _sdkStatus = 'idle' // 'idle' | 'loading' | 'ready'
const _sdkWaiters = []

function ensureKakaoSdk(cb) {
  if (_sdkStatus === 'ready') { cb(); return }
  _sdkWaiters.push(cb)
  if (_sdkStatus === 'loading') return
  _sdkStatus = 'loading'

  const s = document.createElement('script')
  // autoload=false: 수동으로 kakao.maps.load() 호출해야 services 포함 완전 초기화 보장
  s.src = `//dapi.kakao.com/v2/maps/sdk.js?appkey=${KAKAO_MAP_KEY}&libraries=services&autoload=false`
  s.onload = () => {
    window.kakao.maps.load(() => {
      _sdkStatus = 'ready'
      _sdkWaiters.splice(0).forEach(fn => fn())
    })
  }
  document.head.appendChild(s)
}
// ────────────────────────────────────────────────────────────────────────────

const initialForm = {
  name: '',
  postalCode: '',
  address: '',
  addressDetail: '',
  phone: '',
  openTime: '',
  closeTime: '',
  ownerUserId: '',
  latitude: null,
  longitude: null,
}

export default function StoreCreatePage() {
  const [form, setForm] = useState(initialForm)
  const [errors, setErrors] = useState({})
  const [successMsg, setSuccessMsg] = useState('')
  const [serverError, setServerError] = useState('')
  const [loading, setLoading] = useState(false)
  const [showOwnerPopup, setShowOwnerPopup] = useState(false)
  const [ownerName, setOwnerName] = useState('')
  const [sdkReady, setSdkReady] = useState(false)
  const [showPostcode, setShowPostcode] = useState(false)
  const [pendingAddress, setPendingAddress] = useState(null)

  const mapRef         = useRef(null)
  const postcodeRef    = useRef(null)
  const mapInstanceRef = useRef(null)
  const markerRef      = useRef(null)
  const mapInitialized = useRef(false)

  // Daum 우편번호 + 카카오 지도 SDK 로드 (한 번만)
  useEffect(() => {
    if (!document.querySelector('script[src*="postcode.v2"]')) {
      const s = document.createElement('script')
      s.src = 'https://t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js'
      s.async = true
      document.head.appendChild(s)
    }
    ensureKakaoSdk(() => setSdkReady(true))
  }, [])

  // 드래그·클릭으로 마커 위치 조정 가능한 지도 생성
  const initPickerMap = useCallback((lat, lng) => {
    if (!mapRef.current || !window.kakao?.maps?.LatLng) return
    const { maps } = window.kakao
    const pos    = new maps.LatLng(lat, lng)
    const map    = new maps.Map(mapRef.current, { center: pos, level: 4 })
    const marker = new maps.Marker({ map, position: pos, draggable: true })

    const reverseGeocode = (lat, lng) => {
      const gc = new maps.services.Geocoder()
      gc.coord2Address(lng, lat, (result, status) => {
        if (status !== maps.services.Status.OK) return
        const road   = result[0]?.road_address
        const jibun  = result[0]?.address
        setForm(prev => ({
          ...prev,
          postalCode: road != null ? (road.zone_no || '') : prev.postalCode,
          address:    road?.address_name || jibun?.address_name || prev.address,
        }))
      })
    }

    maps.event.addListener(marker, 'dragend', () => {
      const p   = marker.getPosition()
      const lat = p.getLat()
      const lng = p.getLng()
      setForm(prev => ({ ...prev, latitude: lat, longitude: lng }))
      reverseGeocode(lat, lng)
    })
    maps.event.addListener(map, 'click', ({ latLng }) => {
      marker.setPosition(latLng)
      const lat = latLng.getLat()
      const lng = latLng.getLng()
      setForm(prev => ({ ...prev, latitude: lat, longitude: lng }))
      reverseGeocode(lat, lng)
    })

    mapInstanceRef.current = map
    markerRef.current      = marker
    mapInitialized.current = true
  }, [])

  // SDK 준비 완료 시 서울 기본값으로 지도 초기화
  useEffect(() => {
    if (!sdkReady || !mapRef.current || mapInitialized.current) return
    initPickerMap(37.5665, 126.978)
    return () => {
      mapInitialized.current = false
      mapInstanceRef.current = null
      markerRef.current = null
    }
  }, [sdkReady, initPickerMap])

  // pendingAddress 변경 시 좌표 변환 후 지도 이동
  // ensureKakaoSdk 가 services 포함 완전 로드를 보장하므로 추가 load() 불필요
  useEffect(() => {
    if (!pendingAddress || !sdkReady || !window.kakao?.maps?.services) return
    const geocoder = new window.kakao.maps.services.Geocoder()
    geocoder.addressSearch(pendingAddress, (result, status) => {
      setPendingAddress(null)
      if (status !== window.kakao.maps.services.Status.OK) return
      const lat = parseFloat(result[0].y)
      const lng = parseFloat(result[0].x)
      setForm(prev => ({ ...prev, latitude: lat, longitude: lng }))
      if (mapInitialized.current && mapInstanceRef.current) {
        mapInstanceRef.current.panTo(new window.kakao.maps.LatLng(lat, lng))
        markerRef.current.setPosition(new window.kakao.maps.LatLng(lat, lng))
      } else if (mapRef.current) {
        initPickerMap(lat, lng)
      }
    })
  }, [pendingAddress, sdkReady, initPickerMap])

  // showPostcode 가 true 가 되면 postcodeRef 컨테이너에 embed
  useEffect(() => {
    if (!showPostcode || !postcodeRef.current || !window.daum?.Postcode) return
    postcodeRef.current.innerHTML = ''
    new window.daum.Postcode({
      oncomplete(data) {
        setShowPostcode(false)
        setForm(prev => ({
          ...prev,
          postalCode: data.zonecode,
          address: data.address,
          latitude: null,
          longitude: null,
        }))
        setErrors(prev => ({ ...prev, postalCode: '', address: '' }))
        setPendingAddress(data.address)
      },
      width: '100%',
      height: '100%',
    }).embed(postcodeRef.current)
  }, [showPostcode])

  const openPostcode = () => setShowPostcode(true)

  const formatPhone = (raw) => {
    const d = raw.replace(/\D/g, '').slice(0, 11)
    if (d.startsWith('02')) {
      if (d.length <= 2)  return d
      if (d.length <= 6)  return `${d.slice(0,2)}-${d.slice(2)}`
      if (d.length <= 9)  return `${d.slice(0,2)}-${d.slice(2,5)}-${d.slice(5)}`
      return `${d.slice(0,2)}-${d.slice(2,6)}-${d.slice(6,10)}`
    }
    if (d.length <= 3)  return d
    if (d.length <= 6)  return `${d.slice(0,3)}-${d.slice(3)}`
    if (d.length <= 10) return `${d.slice(0,3)}-${d.slice(3,6)}-${d.slice(6)}`
    return `${d.slice(0,3)}-${d.slice(3,7)}-${d.slice(7,11)}`
  }

  const handleChange = (e) => {
    const { name, value } = e.target
    setForm(prev => ({ ...prev, [name]: value }))
    setErrors(prev => ({ ...prev, [name]: '' }))
    setSuccessMsg('')
    setServerError('')
  }

  const validate = () => {
    const next = {}
    if (!form.name)        next.name        = '매장명을 입력해주세요'
    if (!form.postalCode)  next.postalCode   = '우편번호를 입력해주세요'
    if (!form.address)     next.address      = '주소를 입력해주세요'
    if (!form.openTime)    next.openTime     = '영업 시작 시간을 입력해주세요'
    if (!form.closeTime)   next.closeTime    = '영업 종료 시간을 입력해주세요'
    if (!form.ownerUserId) next.ownerUserId  = '점주를 선택해주세요'
    return next
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    const errs = validate()
    if (Object.keys(errs).length > 0) { setErrors(errs); return }
    setLoading(true)
    try {
      const payload = { ...form, addressDetail: form.addressDetail || null, phone: form.phone || null }
      const res = await createStore(payload)
      const { storeId, name } = res.data.data
      setSuccessMsg(`매장이 생성됐습니다! (ID: ${storeId}, 매장명: ${name})`)
      setForm(initialForm)
    } catch (err) {
      setServerError(err.response?.data?.message || '매장 생성 중 오류가 발생했습니다')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AdminLayout>
      <h1 className={styles.pageTitle}>매장 생성</h1>

      <div className={styles.card}>
        <form onSubmit={handleSubmit} noValidate>

          <p className={styles.sectionTitle}>기본 정보</p>
          <div className={styles.formRow}>
            <Field label="매장명" name="name" required
              value={form.name} onChange={handleChange} error={errors.name}
              placeholder="예) 스타벅스 강남점" styles={styles} />
            <Field label="전화번호" name="phone"
              value={form.phone}
              onChange={(e) => setForm(prev => ({ ...prev, phone: formatPhone(e.target.value) }))}
              placeholder="숫자만 입력하면 자동 포맷됩니다" styles={styles} />
          </div>

          <p className={styles.sectionTitle}>주소 정보</p>
          <div className={styles.field}>
            <label className={styles.label}>우편번호 <span className={styles.required}> *</span></label>
            <div className={styles.ownerRow}>
              <input className={`${styles.input} ${errors.postalCode ? styles.inputError : ''}`}
                value={form.postalCode} placeholder="주소 검색 버튼을 눌러주세요" readOnly />
              <button type="button" className={styles.searchIconBtn} onClick={openPostcode}>
                🔍 주소 검색
              </button>
            </div>
            {errors.postalCode && <span className={styles.errorMsg}>{errors.postalCode}</span>}
          </div>
          <div className={styles.field}>
            <label className={styles.label}>도로명 주소 <span className={styles.required}> *</span></label>
            <div className={styles.addressRow}>
              <input className={`${styles.input} ${errors.address ? styles.inputError : ''}`}
                value={form.address} placeholder="주소 검색 후 자동 입력됩니다" readOnly />
              {form.latitude && <span className={styles.coordBadge}>📍 좌표 저장됨</span>}
            </div>
            {errors.address && <span className={styles.errorMsg}>{errors.address}</span>}
          </div>
          <Field label="상세 주소" name="addressDetail"
            value={form.addressDetail} onChange={handleChange}
            placeholder="예) 2층 201호" styles={styles} />

          <p className={styles.sectionTitle}>위치 선택</p>
          <p className={styles.mapPickerHint}>
            주소 검색 후 마커를 드래그하거나 지도를 클릭해 정확한 위치를 조정하세요
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
          <div className={styles.formRow}>
            <Field label="영업 시작" name="openTime" type="time" required
              value={form.openTime} onChange={handleChange} error={errors.openTime} styles={styles} />
            <Field label="영업 종료" name="closeTime" type="time" required
              value={form.closeTime} onChange={handleChange} error={errors.closeTime} styles={styles} />
          </div>

          <p className={styles.sectionTitle}>점주 정보</p>
          <div className={styles.field}>
            <label className={styles.label}>점주 아이디 <span className={styles.required}> *</span></label>
            <div className={styles.ownerRow}>
              <input
                className={`${styles.input} ${errors.ownerUserId ? styles.inputError : ''}`}
                name="ownerUserId"
                value={form.ownerUserId ? `${form.ownerUserId} / ${ownerName}` : ''}
                onChange={handleChange}
                placeholder="점주 회원 아이디"
                autoComplete="off"
                readOnly
              />
              <button type="button" className={styles.searchIconBtn}
                onClick={() => setShowOwnerPopup(true)} title="점주 검색">🔍</button>
            </div>
            {errors.ownerUserId && <span className={styles.errorMsg}>{errors.ownerUserId}</span>}
          </div>

          <div className={styles.submitArea}>
            <button type="button" className={styles.resetBtn} onClick={() => {
              setForm(initialForm); setErrors({}); setSuccessMsg(''); setServerError('')
              mapInitialized.current = false; mapInstanceRef.current = null; markerRef.current = null
            }}>초기화</button>
            <button type="submit" className={styles.submitBtn} disabled={loading}>
              {loading ? '생성 중...' : '매장 생성'}
            </button>
          </div>
        </form>

        {successMsg && <p className={styles.alertSuccess}>{successMsg}</p>}
        {serverError && <p className={styles.alertError}>{serverError}</p>}
      </div>

      {showOwnerPopup && (
        <OwnerSearchPopup
          onSelect={(member) => {
            setForm(prev => ({ ...prev, ownerUserId: member.userId }))
            setOwnerName(member.name)
            setErrors(prev => ({ ...prev, ownerUserId: '' }))
          }}
          onClose={() => setShowOwnerPopup(false)}
        />
      )}

      {showPostcode && (
        <div className={styles.postcodeOverlay}>
          <div className={styles.postcodeModal}>
            <div className={styles.postcodeHeader}>
              <span>주소 검색</span>
              <button className={styles.postcodeCloseBtn} onClick={() => setShowPostcode(false)}>✕</button>
            </div>
            <div ref={postcodeRef} className={styles.postcodeEmbed} />
          </div>
        </div>
      )}
    </AdminLayout>
  )
}

function Field({ label, name, type = 'text', required, value, onChange, error, placeholder, maxLength, styles }) {
  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={name}>
        {label}{required && <span className={styles.required}> *</span>}
      </label>
      <input
        className={`${styles.input} ${error ? styles.inputError : ''}`}
        id={name} name={name} type={type} value={value} onChange={onChange}
        placeholder={placeholder} maxLength={maxLength} autoComplete="off"
      />
      {error && <span className={styles.errorMsg}>{error}</span>}
    </div>
  )
}