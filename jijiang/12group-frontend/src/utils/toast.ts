export function toast(title: string, icon: UniApp.ShowToastOptions["icon"] = "none") {
  uni.showToast({ title, icon });
}

export function modal(title: string, content: string) {
  return uni.showModal({ title, content, confirmColor: "#1F4FD8" });
}
