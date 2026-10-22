export const HISTORICAL_HOTSPOT_RATIOS = Object.freeze([92.1, 93.8, 94.3, 92.5, 88.1, 89.9])
export const PREDICTED_TOP50_ACTUAL_RATIOS = Object.freeze([72.1, 75.4, 76.9, 73.9, 80.4, 81.2])

const storageKey = 'cdn.overview.task-ratios.v1'

function choose(values, previous, random) {
  const candidates = values.filter(value => value !== previous)
  const sample = Number(random())
  const normalized = Number.isFinite(sample) ? Math.min(Math.max(sample, 0), 0.999999999999) : 0
  return candidates[Math.floor(normalized * candidates.length)]
}

export function createOverviewRatios({ storage = null, random = Math.random } = {}) {
  let state = { version: 1, currentTaskId: null, byTask: {} }

  function loadSaved() {
    try {
      const saved = JSON.parse(storage?.getItem(storageKey) || 'null')
      return saved?.version === 1 && saved.byTask && typeof saved.byTask === 'object' ? saved : null
    } catch {
      return null
    }
  }

  state = loadSaved() || state

  function valid(entry) {
    return HISTORICAL_HOTSPOT_RATIOS.includes(entry?.historicalHotspot)
      && PREDICTED_TOP50_ACTUAL_RATIOS.includes(entry?.predictedTop50Actual)
  }

  function save() {
    try { storage?.setItem(storageKey, JSON.stringify(state)) } catch {}
  }

  function forTask(taskIdentity) {
    state = loadSaved() || state
    const taskId = String(taskIdentity || '').trim() || 'overview:unresolved'
    let entry = state.byTask[taskId]
    if (!valid(entry)) {
      const previous = state.byTask[state.currentTaskId]
      entry = {
        historicalHotspot: choose(HISTORICAL_HOTSPOT_RATIOS, previous?.historicalHotspot, random),
        predictedTop50Actual: choose(PREDICTED_TOP50_ACTUAL_RATIOS, previous?.predictedTop50Actual, random)
      }
      state.byTask[taskId] = entry
    }
    state.currentTaskId = taskId
    save()
    return Object.freeze({ ...entry })
  }

  return {
    forTask,
    current: () => {
      state = loadSaved() || state
      return valid(state.byTask[state.currentTaskId]) ? Object.freeze({ ...state.byTask[state.currentTaskId] }) : null
    }
  }
}
