export const HOME_ROUTE = "/pages/tabbar/home/index";
export const LOGIN_ROUTE = "/pages/login/index";

const BUYER_TAB_ROUTES = new Set([
  "/pages/tabbar/home/index",
  "/pages/tabbar/discover/index",
  "/pages/tabbar/message/index",
  "/pages/tabbar/mine/index",
]);

const SELLER_ROOT_ROUTES = new Set([
  "/pages/tabbar/seller-desk/index",
  "/pages/tabbar/seller-order/index",
  "/pages/tabbar/seller-service/index",
]);

export function go(url: string) {
  uni.navigateTo({ url });
}

export function tab(url: string) {
  uni.switchTab({ url });
}

export function buildLoginUrl(redirect = HOME_ROUTE, message?: string) {
  const query: string[] = [];
  const normalizedRedirect = redirect || HOME_ROUTE;
  query.push(`redirect=${encodeURIComponent(normalizedRedirect)}`);
  if (message) {
    query.push(`message=${encodeURIComponent(message)}`);
  }
  return `${LOGIN_ROUTE}?${query.join("&")}`;
}

export function openLoginPage(redirect = getCurrentRouteWithQuery(), message?: string) {
  uni.navigateTo({ url: buildLoginUrl(redirect, message) });
}

export function redirectToLogin(redirect = getCurrentRouteWithQuery(), message?: string) {
  if (isLoginRoute(getCurrentRouteWithQuery())) {
    return;
  }
  try {
    uni.reLaunch({ url: buildLoginUrl(redirect, message) });
  } catch {
    // Ignore navigation errors and keep the current page visible.
  }
}

export function navigateAfterLogin(target = HOME_ROUTE) {
  const normalizedTarget = target || HOME_ROUTE;
  const path = stripQuery(normalizedTarget);

  return new Promise<void>((resolve, reject) => {
    const success = () => resolve();
    const fail = (error: unknown) => reject(error);

    if (BUYER_TAB_ROUTES.has(path)) {
      uni.switchTab({ url: path, success, fail });
      return;
    }

    if (SELLER_ROOT_ROUTES.has(path)) {
      uni.reLaunch({ url: normalizedTarget, success, fail });
      return;
    }

    uni.redirectTo({ url: normalizedTarget, success, fail });
  });
}

export function getCurrentRouteWithQuery() {
  const pages = getCurrentPages();
  if (!pages.length) return HOME_ROUTE;

  const current = pages[pages.length - 1];
  const route = current?.route ? `/${current.route}` : HOME_ROUTE;
  const currentPage = (current as Page.PageInstance & { options?: Record<string, unknown> }) || null;
  const options = currentPage?.options || {};
  const query = Object.entries(options)
    .filter(([, value]) => value !== undefined && value !== null && String(value) !== "")
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join("&");

  return query ? `${route}?${query}` : route;
}

export function requireLogin(token: string, redirect?: string) {
  if (token) return true;
  openLoginPage(redirect);
  return false;
}

function isLoginRoute(url: string) {
  return stripQuery(url) === LOGIN_ROUTE;
}

function stripQuery(url: string) {
  return url.split("?")[0] || url;
}
