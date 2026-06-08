const allowedImageUrlPattern = /^(https?:\/\/|wxfile:\/\/|cloud:\/\/|data:image\/|\/static\/|\/assets\/)/i;
const knownUnavailableImageUrls = new Set([
  "https://jijiang-1325125602.cos.ap-beijing.myqcloud.com/service/covers/python-spider.png",
]);

export function normalizeImageUrl(value?: string | null) {
  const url = String(value || "").trim();
  if (!url) return "";
  if (knownUnavailableImageUrls.has(url)) return "";
  if (!allowedImageUrlPattern.test(url)) return "";
  return url;
}

export function imageBackground(value?: string | null) {
  const url = normalizeImageUrl(value);
  return url ? `url("${url.replace(/"/g, "%22")}")` : "";
}
