import { useState, useEffect, useRef, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { getEvent, getQueueStatus, joinQueue, purchaseEvent, subscribeSse } from '../../api/event'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './EventDetailPage.module.css'

const PHASE = {
  LOADING:   'loading',
  SCHEDULED: 'scheduled',  // 오픈 전
  OPEN:      'open',       // 대기열 미입장
  JOINING:   'joining',    // 입장 중 (429 재시도)
  WAITING:   'waiting',    // 대기 중
  READY:     'ready',      // 구매 가능
  DONE:      'done',       // 구매 완료
  CLOSED:    'closed',     // 이벤트 종료
}

const MAX_JOIN_RETRIES = 10

/**
 * 고객 한정판매 이벤트 상세 페이지. (@route /events/:eventId)
 *
 * 페이즈(PHASE) 기반 UI 상태 머신:
 * - LOADING  → 초기 데이터 로드 중
 * - SCHEDULED→ 오픈 전 (오픈 예정 시각 표시)
 * - OPEN     → 대기열 미입장 (입장 버튼 표시)
 * - JOINING  → 대기열 입장 중 (429 Too Many Requests 시 최대 10회 자동 재시도)
 * - WAITING  → 대기 중 (SSE로 실시간 순번 수신)
 * - READY    → 구매 가능 (5분 카운트다운 시작, 만료 시 OPEN으로 복귀)
 * - DONE     → 구매 완료
 * - CLOSED   → 이벤트 종료
 *
 * SSE(/api/events/:eventId/queue/subscribe) 구독으로 순번·구매 가능 상태 실시간 수신.
 * 언마운트 시 SSE 연결 자동 해제.
 */
export default function EventDetailPage() {
  const { eventId } = useParams()
  const navigate = useNavigate()
  const [event, setEvent] = useState(null)
  const [phase, setPhase] = useState(PHASE.LOADING)
  const [position, setPosition] = useState(null)
  const [readySec, setReadySec] = useState(300)
  const [error, setError] = useState('')
  const sseCloseRef = useRef(null)
  const joinRetryRef = useRef(0)

  const connectSse = useCallback(() => {
    if (sseCloseRef.current) sseCloseRef.current()
    sseCloseRef.current = subscribeSse(eventId, {
      onQueue: (data) => setPosition(data.position),
      onReady: ()     => setPhase(PHASE.READY),
    })
  }, [eventId])

  // 이벤트 정보 + 내 상태 로드
  useEffect(() => {
    Promise.all([getEvent(eventId), getQueueStatus(eventId)])
      .then(([evRes, stRes]) => {
        const ev = evRes.data.data
        const st = stRes.data.data
        setEvent(ev)

        if (st.purchased) {
          setPhase(PHASE.DONE)
        } else if (st.isReady) {
          setPhase(PHASE.READY)
        } else if (st.inQueue) {
          setPosition(st.position)
          setPhase(PHASE.WAITING)
          connectSse()
        } else if (ev.status === 'CLOSED') {
          setPhase(PHASE.CLOSED)
        } else if (ev.status === 'SCHEDULED') {
          setPhase(PHASE.SCHEDULED)
        } else {
          setPhase(PHASE.OPEN)
        }
      })
      .catch(() => setPhase(PHASE.CLOSED))
  }, [eventId, connectSse])

  // 대기열 입장 (429 시 자동 재시도)
  const handleJoin = useCallback(async () => {
    setPhase(PHASE.JOINING)
    setError('')
    joinRetryRef.current = 0

    const attempt = async () => {
      try {
        await joinQueue(eventId)
        joinRetryRef.current = 0
        setPhase(PHASE.WAITING)
        connectSse()
      } catch (err) {
        if (err.response?.status === 429 && joinRetryRef.current < MAX_JOIN_RETRIES) {
          joinRetryRef.current++
          setTimeout(attempt, 500)
        } else {
          setPhase(PHASE.OPEN)
          setError('입장에 실패했습니다. 다시 시도해주세요.')
        }
      }
    }
    attempt()
  }, [eventId, connectSse])

  // 구매
  const handlePurchase = async () => {
    setError('')
    try {
      await purchaseEvent(eventId)
      if (sseCloseRef.current) sseCloseRef.current()
      setPhase(PHASE.DONE)
    } catch (err) {
      setError(err.response?.data?.message || '구매에 실패했습니다.')
    }
  }

  // READY 5분 카운트다운
  useEffect(() => {
    if (phase !== PHASE.READY) return
    setReadySec(300)
    const timer = setInterval(() => {
      setReadySec((s) => {
        if (s <= 1) {
          clearInterval(timer)
          setPhase(PHASE.OPEN)
          return 0
        }
        return s - 1
      })
    }, 1000)
    return () => clearInterval(timer)
  }, [phase])

  // 언마운트 시 SSE 정리
  useEffect(() => () => { if (sseCloseRef.current) sseCloseRef.current() }, [])

  if (!event && phase === PHASE.LOADING) {
    return <CustomerLayout><div className={styles.loading}>불러오는 중...</div></CustomerLayout>
  }

  const formatTime = (sec) => `${String(Math.floor(sec / 60)).padStart(2, '0')}:${String(sec % 60).padStart(2, '0')}`

  return (
    <CustomerLayout>
      <button className={styles.back} onClick={() => navigate('/events')}>← 한정판매 목록</button>

      {event && (
        <div className={styles.productCard}>
          <div className={styles.productImage}>
            {event.goodsImageUrl
              ? <img src={event.goodsImageUrl} alt={event.goodsName} />
              : <span className={styles.productEmoji}>🎁</span>
            }
          </div>
          <div className={styles.productInfo}>
            <p className={styles.eventLabel}>{event.name}</p>
            <h2 className={styles.goodsName}>{event.goodsName}</h2>
            {event.goodsDescription && <p className={styles.goodsDesc}>{event.goodsDescription}</p>}
            <p className={styles.price}>{event.goodsPrice?.toLocaleString()}원</p>
            <div className={styles.capacityRow}>
              <span>총 {event.capacity}개</span>
              {event.status === 'OPEN' && (
                <span className={styles.remaining}>잔여 {event.remainingCapacity}개</span>
              )}
            </div>
          </div>
        </div>
      )}

      <div className={styles.actionArea}>
        {error && <div className={styles.errorBox}>{error}</div>}

        {phase === PHASE.LOADING && (
          <div className={styles.statusBox}>상태 확인 중...</div>
        )}

        {phase === PHASE.SCHEDULED && (
          <div className={styles.statusBox}>
            <div className={styles.statusIcon}>⏳</div>
            <p className={styles.statusTitle}>이벤트 오픈 준비 중</p>
            <p className={styles.statusDesc}>
              {event && new Date(event.openAt).toLocaleString('ko-KR')} 오픈 예정
            </p>
          </div>
        )}

        {phase === PHASE.OPEN && (
          <div className={styles.statusBox}>
            <div className={styles.statusIcon}>🎯</div>
            <p className={styles.statusTitle}>선착순 구매 진행 중</p>
            <p className={styles.statusDesc}>대기열에 입장하면 순서대로 구매 기회가 주어집니다</p>
            <button className={styles.joinBtn} onClick={handleJoin}>대기열 입장하기</button>
          </div>
        )}

        {phase === PHASE.JOINING && (
          <div className={styles.statusBox}>
            <div className={`${styles.statusIcon} ${styles.spin}`}>🔄</div>
            <p className={styles.statusTitle}>입장 중...</p>
            <p className={styles.statusDesc}>잠시만 기다려주세요</p>
          </div>
        )}

        {phase === PHASE.WAITING && (
          <div className={styles.statusBox}>
            <div className={styles.statusIcon}>🎫</div>
            <p className={styles.statusTitle}>대기 중</p>
            {position !== null && position >= 0
              ? <div className={styles.positionBadge}>{position + 1}번째</div>
              : <div className={styles.positionBadge}>순번 확인 중...</div>
            }
            <p className={styles.statusDesc}>순서가 되면 자동으로 알려드립니다</p>
          </div>
        )}

        {phase === PHASE.READY && (
          <div className={`${styles.statusBox} ${styles.readyBox}`}>
            <div className={styles.statusIcon}>🛒</div>
            <p className={styles.statusTitle}>구매 가능합니다!</p>
            <div className={styles.countdown}>{formatTime(readySec)}</div>
            <p className={styles.statusDesc}>시간이 지나면 기회가 사라집니다</p>
            <button className={styles.purchaseBtn} onClick={handlePurchase}>지금 구매하기</button>
          </div>
        )}

        {phase === PHASE.DONE && (
          <div className={`${styles.statusBox} ${styles.doneBox}`}>
            <div className={styles.statusIcon}>✅</div>
            <p className={styles.statusTitle}>구매 완료!</p>
            <p className={styles.statusDesc}>한정판매 굿즈를 성공적으로 구매했습니다</p>
          </div>
        )}

        {phase === PHASE.CLOSED && (
          <div className={styles.statusBox}>
            <div className={styles.statusIcon}>🚫</div>
            <p className={styles.statusTitle}>이벤트 종료</p>
            <p className={styles.statusDesc}>모든 수량이 소진되었습니다</p>
          </div>
        )}
      </div>
    </CustomerLayout>
  )
}
