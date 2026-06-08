export function go(url: string) {
  uni.navigateTo({ url });
}

export function tab(url: string) {
  uni.switchTab({ url });
}

export function requireLogin(token: string, redirect?: string) {
  if (token) return true;
  const next = redirect ? `?redirect=${encodeURIComponent(redirect)}` : "";
  uni.navigateTo({ url: `/pages/login/index${next}` });
  return false;
}
