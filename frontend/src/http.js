export async function requestJson(path, fetchService = fetch, options) {
  const response = await fetchService(path, options)
  if (!response.ok) {
    let message = `请求失败（HTTP ${response.status}）`
    try {
      const payload = await response.json()
      const detail = payload.error || payload
      if (detail.message) message = detail.code ? `${detail.code}：${detail.message}` : detail.message
      else if (typeof payload.detail === 'string') message = payload.detail
    } catch {}
    const error = new Error(message)
    error.status = response.status
    throw error
  }
  return response.json()
}
