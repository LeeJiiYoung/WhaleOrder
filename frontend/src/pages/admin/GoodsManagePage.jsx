import { useState, useEffect, useCallback, useRef } from 'react'
import { createGoods, getAdminGoods } from '../../api/event'
import { getStores } from '../../api/store'
import AdminLayout from '../../components/admin/AdminLayout'
import styles from './EventManagePage.module.css'
import uploadStyles from './GoodsManagePage.module.css'

export default function GoodsManagePage() {
  const [stores,    setStores]    = useState([])
  const [goodsList, setGoodsList] = useState([])
  const [loading,   setLoading]   = useState(true)
  const [msg,       setMsg]       = useState('')

  const [form, setForm] = useState({ name: '', description: '', price: '', storeId: '' })
  const [imageFile, setImageFile] = useState(null)
  const [preview,   setPreview]   = useState(null)
  const [imageError, setImageError] = useState('')
  const [saving, setSaving] = useState(false)

  const fileInputRef = useRef(null)

  const flash = (text) => { setMsg(text); setTimeout(() => setMsg(''), 3000) }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [stRes, gRes] = await Promise.all([getStores(), getAdminGoods()])
      setStores(stRes.data.data)
      setGoodsList(gRes.data.data)
    } catch {
      flash('데이터를 불러오지 못했습니다')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  const handleImageChange = (file) => {
    if (!file) return
    if (!file.type.startsWith('image/')) { setImageError('이미지 파일만 업로드할 수 있습니다'); return }
    if (file.size > 10 * 1024 * 1024)    { setImageError('10MB 이하 파일만 업로드 가능합니다'); return }
    setImageFile(file)
    setPreview(URL.createObjectURL(file))
    setImageError('')
  }

  const handleDrop = (e) => {
    e.preventDefault()
    handleImageChange(e.dataTransfer.files[0])
  }

  const resetForm = () => {
    setForm({ name: '', description: '', price: '', storeId: '' })
    setImageFile(null)
    setPreview(null)
    setImageError('')
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setSaving(true)
    try {
      const formData = new FormData()
      formData.append('name',        form.name)
      formData.append('price',       form.price)
      formData.append('storeId',     form.storeId)
      if (form.description) formData.append('description', form.description)
      if (imageFile)        formData.append('imageFile',   imageFile)

      await createGoods(formData)
      resetForm()
      flash('굿즈가 등록됐습니다')
      load()
    } catch (err) {
      flash(err.response?.data?.message || '굿즈 등록 실패')
    } finally {
      setSaving(false)
    }
  }

  return (
    <AdminLayout>
      <div className={styles.header}>
        <h2 className={styles.title}>🎁 굿즈 관리</h2>
        {msg && <span className={styles.flash}>{msg}</span>}
      </div>

      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>굿즈 등록</h3>
        <form className={uploadStyles.layout} onSubmit={handleSubmit}>

          {/* 이미지 업로드 */}
          <div className={uploadStyles.imageCol}>
            <div
              className={`${uploadStyles.uploadArea} ${imageError ? uploadStyles.uploadAreaError : ''}`}
              onClick={() => fileInputRef.current?.click()}
              onDragOver={(e) => e.preventDefault()}
              onDrop={handleDrop}
            >
              {preview ? (
                <img src={preview} alt="미리보기" className={uploadStyles.previewImage} />
              ) : (
                <div className={uploadStyles.placeholder}>
                  <span className={uploadStyles.uploadIcon}>🖼</span>
                  <p className={uploadStyles.uploadText}>클릭 또는 드래그하여 업로드</p>
                  <p className={uploadStyles.uploadHint}>PNG, JPG, WEBP · 최대 10MB</p>
                </div>
              )}
            </div>
            {imageError && <p className={uploadStyles.imgError}>{imageError}</p>}
            {preview && (
              <button type="button" className={uploadStyles.changeBtn}
                onClick={() => fileInputRef.current?.click()}>
                이미지 변경
              </button>
            )}
            <input ref={fileInputRef} type="file" accept="image/*" hidden
              onChange={(e) => handleImageChange(e.target.files[0])} />
          </div>

          {/* 폼 필드 */}
          <div className={uploadStyles.formCol}>
            <div className={styles.row}>
              <label>매장</label>
              <select value={form.storeId} onChange={e => setForm(p => ({...p, storeId: e.target.value}))} required>
                <option value="">매장 선택</option>
                {stores.map(s => <option key={s.storeId} value={s.storeId}>{s.name}</option>)}
              </select>
            </div>
            <div className={styles.row}>
              <label>굿즈명</label>
              <input value={form.name} onChange={e => setForm(p => ({...p, name: e.target.value}))}
                required placeholder="ex. 화이트 텀블러 500ml" />
            </div>
            <div className={styles.row}>
              <label>설명</label>
              <input value={form.description} onChange={e => setForm(p => ({...p, description: e.target.value}))}
                placeholder="선택 입력" />
            </div>
            <div className={styles.row}>
              <label>가격 (원)</label>
              <input type="number" min="0" value={form.price}
                onChange={e => setForm(p => ({...p, price: e.target.value}))}
                required placeholder="25000" />
            </div>
            <button className={styles.submitBtn} type="submit" disabled={saving}>
              {saving ? '등록 중...' : '굿즈 등록'}
            </button>
          </div>

        </form>
      </section>

      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>굿즈 목록</h3>
        {loading ? (
          <p className={styles.empty}>불러오는 중...</p>
        ) : goodsList.length === 0 ? (
          <p className={styles.empty}>등록된 굿즈가 없습니다</p>
        ) : (
          <table className={styles.table}>
            <thead>
              <tr><th>ID</th><th>이미지</th><th>굿즈명</th><th>매장</th><th>가격</th><th>설명</th></tr>
            </thead>
            <tbody>
              {goodsList.map(g => (
                <tr key={g.goodsId}>
                  <td>{g.goodsId}</td>
                  <td>
                    {g.imageUrl
                      ? <img src={g.imageUrl} alt={g.name} className={uploadStyles.thumbImg} />
                      : <span className={uploadStyles.noImg}>-</span>}
                  </td>
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
    </AdminLayout>
  )
}
