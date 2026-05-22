import { useState, useEffect, useCallback } from 'react'
import {
  createGoods, getAdminGoods,
  createAdminEvent, getAdminEvents,
  openAdminEvent, closeAdminEvent,
} from '../../api/event'
import { getStores } from '../../api/store'
import AdminLayout from '../../components/admin/AdminLayout'
import styles from './EventManagePage.module.css'

const STATUS_LABEL = { SCHEDULED: '예정', OPEN: '진행 중', CLOSED: '종료' }
const STATUS_CLASS  = { SCHEDULED: styles.statusScheduled, OPEN: styles.statusOpen, CLOSED: styles.statusClosed }

export default function EventManagePage() {
  const [tab, setTab] = useState('event')   // 'event' | 'goods'

  const [stores,     setStores]     = useState([])
  const [goodsList,  setGoodsList]  = useState([])
  const [eventList,  setEventList]  = useState([])
  const [loading,    setLoading]    = useState(true)
  const [msg,        setMsg]        = useState('')

  // 굿즈 등록 폼
  const [gForm, setGForm] = useState({ name: '', description: '', price: '', storeId: '', imageUrl: '' })
  const [gSaving, setGSaving] = useState(false)

  // 이벤트 등록 폼
  const [eForm, setEForm] = useState({ name: '', goodsId: '', storeId: '', openAt: '', capacity: '', perPersonLimit: '1' })
  const [eSaving, setESaving] = useState(false)

  const flash = (text) => { setMsg(text); setTimeout(() => setMsg(''), 3000) }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [stRes, gRes, eRes] = await Promise.all([getStores(), getAdminGoods(), getAdminEvents()])
      setStores(stRes.data.data)
      setGoodsList(gRes.data.data)
      setEventList(eRes.data.data)
    } catch {
      flash('데이터를 불러오지 못했습니다')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const handleGoodsSubmit = async (e) => {
    e.preventDefault()
    setGSaving(true)
    try {
      await createGoods({ ...gForm, price: Number(gForm.price), storeId: Number(gForm.storeId) })
      setGForm({ name: '', description: '', price: '', storeId: '', imageUrl: '' })
      flash('굿즈가 등록됐습니다')
      load()
    } catch (err) {
      flash(err.response?.data?.message || '굿즈 등록 실패')
    } finally {
      setGSaving(false)
    }
  }

  const handleEventSubmit = async (e) => {
    e.preventDefault()
    setESaving(true)
    try {
      await createAdminEvent({
        ...eForm,
        goodsId: Number(eForm.goodsId),
        storeId: Number(eForm.storeId),
        capacity: Number(eForm.capacity),
        perPersonLimit: Number(eForm.perPersonLimit),
      })
      setEForm({ name: '', goodsId: '', storeId: '', openAt: '', capacity: '', perPersonLimit: '1' })
      flash('이벤트가 등록됐습니다')
      load()
    } catch (err) {
      flash(err.response?.data?.message || '이벤트 등록 실패')
    } finally {
      setESaving(false)
    }
  }

  const handleOpen = async (eventId) => {
    try { await openAdminEvent(eventId); load() } catch { flash('오픈 실패') }
  }
  const handleClose = async (eventId) => {
    try { await closeAdminEvent(eventId); load() } catch { flash('종료 실패') }
  }

  return (
    <AdminLayout>
      <div className={styles.header}>
        <h2 className={styles.title}>🎁 한정판매 관리</h2>
        {msg && <span className={styles.flash}>{msg}</span>}
      </div>

      <div className={styles.tabs}>
        <button className={`${styles.tab} ${tab === 'event' ? styles.tabActive : ''}`} onClick={() => setTab('event')}>이벤트</button>
        <button className={`${styles.tab} ${tab === 'goods' ? styles.tabActive : ''}`} onClick={() => setTab('goods')}>굿즈</button>
      </div>

      {/* ── 이벤트 탭 ── */}
      {tab === 'event' && (
        <>
          <section className={styles.section}>
            <h3 className={styles.sectionTitle}>이벤트 등록</h3>
            <form className={styles.form} onSubmit={handleEventSubmit}>
              <div className={styles.row}>
                <label>이벤트명</label>
                <input value={eForm.name} onChange={e => setEForm(p => ({...p, name: e.target.value}))} required placeholder="ex. 스타벅스 텀블러 선착순 이벤트" />
              </div>
              <div className={styles.row}>
                <label>굿즈</label>
                <select value={eForm.goodsId} onChange={e => setEForm(p => ({...p, goodsId: e.target.value}))} required>
                  <option value="">굿즈 선택</option>
                  {goodsList.map(g => <option key={g.goodsId} value={g.goodsId}>{g.name} ({g.storeName})</option>)}
                </select>
              </div>
              <div className={styles.row}>
                <label>매장</label>
                <select value={eForm.storeId} onChange={e => setEForm(p => ({...p, storeId: e.target.value}))} required>
                  <option value="">매장 선택</option>
                  {stores.map(s => <option key={s.storeId} value={s.storeId}>{s.name}</option>)}
                </select>
              </div>
              <div className={styles.row}>
                <label>오픈 시각</label>
                <input type="datetime-local" value={eForm.openAt} onChange={e => setEForm(p => ({...p, openAt: e.target.value}))} required />
              </div>
              <div className={styles.rowHalf}>
                <div className={styles.row}>
                  <label>총 수량</label>
                  <input type="number" min="1" value={eForm.capacity} onChange={e => setEForm(p => ({...p, capacity: e.target.value}))} required placeholder="100" />
                </div>
                <div className={styles.row}>
                  <label>1인 한도</label>
                  <input type="number" min="1" value={eForm.perPersonLimit} onChange={e => setEForm(p => ({...p, perPersonLimit: e.target.value}))} required />
                </div>
              </div>
              <button className={styles.submitBtn} type="submit" disabled={eSaving}>{eSaving ? '등록 중...' : '이벤트 등록'}</button>
            </form>
          </section>

          <section className={styles.section}>
            <h3 className={styles.sectionTitle}>이벤트 목록</h3>
            {loading ? <p className={styles.empty}>불러오는 중...</p> : eventList.length === 0 ? <p className={styles.empty}>등록된 이벤트가 없습니다</p> : (
              <table className={styles.table}>
                <thead>
                  <tr><th>ID</th><th>이벤트명</th><th>굿즈</th><th>오픈 시각</th><th>수량</th><th>상태</th><th>작업</th></tr>
                </thead>
                <tbody>
                  {eventList.map(ev => (
                    <tr key={ev.eventId}>
                      <td>{ev.eventId}</td>
                      <td>{ev.name}</td>
                      <td>{ev.goodsName}</td>
                      <td>{new Date(ev.openAt).toLocaleString('ko-KR')}</td>
                      <td>{ev.remainingCapacity} / {ev.capacity}</td>
                      <td><span className={`${styles.status} ${STATUS_CLASS[ev.status]}`}>{STATUS_LABEL[ev.status]}</span></td>
                      <td className={styles.actions}>
                        {ev.status === 'SCHEDULED' && <button className={styles.openBtn} onClick={() => handleOpen(ev.eventId)}>강제 오픈</button>}
                        {ev.status === 'OPEN'      && <button className={styles.closeBtn} onClick={() => handleClose(ev.eventId)}>종료</button>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}

      {/* ── 굿즈 탭 ── */}
      {tab === 'goods' && (
        <>
          <section className={styles.section}>
            <h3 className={styles.sectionTitle}>굿즈 등록</h3>
            <form className={styles.form} onSubmit={handleGoodsSubmit}>
              <div className={styles.row}>
                <label>매장</label>
                <select value={gForm.storeId} onChange={e => setGForm(p => ({...p, storeId: e.target.value}))} required>
                  <option value="">매장 선택</option>
                  {stores.map(s => <option key={s.storeId} value={s.storeId}>{s.name}</option>)}
                </select>
              </div>
              <div className={styles.row}>
                <label>굿즈명</label>
                <input value={gForm.name} onChange={e => setGForm(p => ({...p, name: e.target.value}))} required placeholder="ex. 화이트 텀블러 500ml" />
              </div>
              <div className={styles.row}>
                <label>설명</label>
                <input value={gForm.description} onChange={e => setGForm(p => ({...p, description: e.target.value}))} placeholder="선택 입력" />
              </div>
              <div className={styles.row}>
                <label>가격 (원)</label>
                <input type="number" min="0" value={gForm.price} onChange={e => setGForm(p => ({...p, price: e.target.value}))} required placeholder="25000" />
              </div>
              <div className={styles.row}>
                <label>이미지 URL</label>
                <input value={gForm.imageUrl} onChange={e => setGForm(p => ({...p, imageUrl: e.target.value}))} placeholder="선택 입력" />
              </div>
              <button className={styles.submitBtn} type="submit" disabled={gSaving}>{gSaving ? '등록 중...' : '굿즈 등록'}</button>
            </form>
          </section>

          <section className={styles.section}>
            <h3 className={styles.sectionTitle}>굿즈 목록</h3>
            {loading ? <p className={styles.empty}>불러오는 중...</p> : goodsList.length === 0 ? <p className={styles.empty}>등록된 굿즈가 없습니다</p> : (
              <table className={styles.table}>
                <thead>
                  <tr><th>ID</th><th>굿즈명</th><th>매장</th><th>가격</th><th>설명</th></tr>
                </thead>
                <tbody>
                  {goodsList.map(g => (
                    <tr key={g.goodsId}>
                      <td>{g.goodsId}</td>
                      <td>{g.name}</td>
                      <td>{g.storeName}</td>
                      <td>{g.price.toLocaleString()}원</td>
                      <td className={styles.desc}>{g.description || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}
    </AdminLayout>
  )
}
