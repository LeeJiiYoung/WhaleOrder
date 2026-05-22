import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { getEvents } from '../../api/event'
import CustomerLayout from '../../components/customer/CustomerLayout'
import styles from './EventListPage.module.css'

function StatusBadge({ status }) {
  if (status === 'OPEN')      return <span className={styles.badgeOpen}>진행 중</span>
  if (status === 'SCHEDULED') return <span className={styles.badgeScheduled}>오픈 예정</span>
  return <span className={styles.badgeClosed}>종료</span>
}

function formatOpenAt(openAt) {
  const d = new Date(openAt)
  return d.toLocaleString('ko-KR', { month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

export default function EventListPage() {
  const navigate = useNavigate()
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    getEvents()
      .then((res) => setEvents(res.data.data))
      .catch(() => setError('이벤트를 불러오지 못했습니다'))
      .finally(() => setLoading(false))
  }, [])

  return (
    <CustomerLayout>
      <div className={styles.header}>
        <h2 className={styles.title}>🎁 한정판매</h2>
        <p className={styles.subtitle}>선착순 특가 굿즈를 지금 만나보세요</p>
      </div>

      {error && <div className={styles.error}>{error}</div>}

      {loading ? (
        <div className={styles.empty}>불러오는 중...</div>
      ) : events.length === 0 ? (
        <div className={styles.empty}>진행 중인 이벤트가 없습니다</div>
      ) : (
        <div className={styles.grid}>
          {events.map((ev) => (
            <button
              key={ev.eventId}
              className={styles.card}
              onClick={() => navigate(`/events/${ev.eventId}`)}
            >
              <div className={styles.imageWrap}>
                {ev.goodsImageUrl ? (
                  <img src={ev.goodsImageUrl} alt={ev.goodsName} className={styles.image} />
                ) : (
                  <div className={styles.imagePlaceholder}>🎁</div>
                )}
                <StatusBadge status={ev.status} />
              </div>
              <div className={styles.cardBody}>
                <p className={styles.eventName}>{ev.name}</p>
                <p className={styles.goodsName}>{ev.goodsName}</p>
                <p className={styles.price}>{ev.goodsPrice.toLocaleString()}원</p>
                <div className={styles.meta}>
                  <span className={styles.openAt}>🕐 {formatOpenAt(ev.openAt)}</span>
                  {ev.status === 'OPEN' && (
                    <span className={styles.remaining}>
                      잔여 {ev.remainingCapacity} / {ev.capacity}
                    </span>
                  )}
                </div>
              </div>
            </button>
          ))}
        </div>
      )}
    </CustomerLayout>
  )
}
