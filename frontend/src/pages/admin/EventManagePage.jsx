import { useState, useEffect, useCallback } from 'react'
import {
  createAdminEvent, getAdminEvents, getAdminGoods,
  openAdminEvent, closeAdminEvent,
} from '../../api/event'
import { getStores } from '../../api/store'
import AdminLayout from '../../components/admin/AdminLayout'
import Breadcrumb from '../../components/admin/Breadcrumb'
import styles from './EventManagePage.module.css'

const STATUS_LABEL = { SCHEDULED: '예정', OPEN: '진행 중', CLOSED: '종료' }
const STATUS_CLASS  = { SCHEDULED: styles.statusScheduled, OPEN: styles.statusOpen, CLOSED: styles.statusClosed }

/**
 * 관리자 한정판매 이벤트 관리 페이지. (@route /admin/events)
 *
 * - 이벤트 등록 폼: 이벤트명·굿즈·매장·오픈 시각·총 수량·1인 구매 한도
 * - 이벤트 목록 테이블: 상태(예정·진행 중·종료) 표시
 * - 예정 이벤트: "강제 오픈" 버튼으로 즉시 개시 가능
 * - 진행 중 이벤트: "종료" 버튼으로 강제 종료
 */
export default function EventManagePage() {
  const [stores,    setStores]    = useState([])
  const [goodsList, setGoodsList] = useState([])
  const [eventList, setEventList] = useState([])
  const [loading,   setLoading]   = useState(true)
  const [msg,       setMsg]       = useState('')

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

  const handleOpen  = async (eventId) => { try { await openAdminEvent(eventId);  load() } catch { flash('오픈 실패') } }
  const handleClose = async (eventId) => { try { await closeAdminEvent(eventId); load() } catch { flash('종료 실패') } }

  return (
    <AdminLayout>
      <Breadcrumb items={[{ label: '한정 판매' }, { label: '이벤트 관리' }]} />
      <div className={styles.header}>
        <h2 className={styles.title}>🎪 이벤트 관리</h2>
        {msg && <span className={styles.flash}>{msg}</span>}
      </div>

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
        {loading ? (
          <p className={styles.empty}>불러오는 중...</p>
        ) : eventList.length === 0 ? (
          <p className={styles.empty}>등록된 이벤트가 없습니다</p>
        ) : (
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
                    {ev.status === 'SCHEDULED' && <button className={styles.openBtn}  onClick={() => handleOpen(ev.eventId)}>강제 오픈</button>}
                    {ev.status === 'OPEN'      && <button className={styles.closeBtn} onClick={() => handleClose(ev.eventId)}>종료</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </AdminLayout>
  )
}
