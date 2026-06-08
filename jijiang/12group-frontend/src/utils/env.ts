const bjtuServiceDefaultApiBase = "http://47.95.238.140:8080";
const configuredApiBase = normalizeApiBase(
  import.meta.env.VITE_API_BASE ||
    (import.meta.env.MODE === "bjtu-service" ? bjtuServiceDefaultApiBase : "")
);
const miniProgramApiBase = normalizeApiBase(
  import.meta.env.VITE_MP_API_BASE || bjtuServiceDefaultApiBase
);
let resolvedApiBase = configuredApiBase;

// Mini Program request APIs require an absolute URL, unlike H5 dev proxy paths.
// #ifdef MP
if (!resolvedApiBase) {
  resolvedApiBase = miniProgramApiBase;
}
// #endif

export const API_BASE = resolvedApiBase;

export function resolveApiUrl(url: string) {
  if (isAbsoluteHttpUrl(url)) return url;

  const path = url.startsWith("/") ? url : `/${url}`;
  let base = API_BASE;

  // #ifdef MP
  if (!base) {
    base = miniProgramApiBase;
  }
  if (!isAbsoluteHttpUrl(base)) {
    throw new Error("VITE_MP_API_BASE must be an absolute http(s) URL for mp-weixin requests.");
  }
  // #endif

  return `${base}${path}`;
}

function normalizeApiBase(value: string) {
  return trimTrailingSlash(value.trim());
}

function trimTrailingSlash(value: string) {
  return value.replace(/\/+$/, "");
}

function isAbsoluteHttpUrl(value: string) {
  return /^https?:\/\//i.test(value);
}
