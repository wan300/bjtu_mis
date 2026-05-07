import { ref } from 'vue'

const alerts = ref([])

let messageApi = null

export function setMessageApi(api) {
  messageApi = api
}

export function useAlerts() {
  function pushAlert(text, type = 'info') {
    alerts.value = [{ message: text, type }, ...alerts.value].slice(0, 3)
    if (messageApi) {
      messageApi[type]?.(text) || messageApi.info(text)
    }
  }

  function dismissAlert(index) {
    alerts.value.splice(index, 1)
  }

  return { alerts, pushAlert, dismissAlert }
}
