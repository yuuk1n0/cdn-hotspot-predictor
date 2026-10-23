export async function checkService(path, fetchService = fetch) {
  try {
    const response = await fetchService(path)
    if (!response.ok) {
      return {
        state: 'down',
        detail: `HTTP ${response.status}`,
        payload: null
      }
    }

    const payload = await response.json()
    return {
      state: 'up',
      detail: `${payload.service} · ${payload.status}`,
      payload
    }
  } catch (error) {
    return {
      state: 'down',
      detail: error instanceof Error ? error.message : '连接失败',
      payload: null
    }
  }
}
