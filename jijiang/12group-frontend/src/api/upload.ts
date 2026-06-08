import { request } from "@/api/request";
import type { UploadToken } from "@/types/domain";
import { toast } from "@/utils/toast";

export interface UploadTokenPayload {
  fileName: string;
  maxSizeBytes: number;
}

export function getUploadToken(data: UploadTokenPayload) {
  return request<UploadToken>({
    url: "/api/user/verify/upload-token",
    method: "POST",
    data: data as unknown as Record<string, unknown>,
  });
}

export function getGenericUploadToken(data: UploadTokenPayload) {
  return request<UploadToken>({
    url: "/api/user/upload-token",
    method: "POST",
    data: data as unknown as Record<string, unknown>,
  });
}

function normalizeLocalImagePath(tempPath: string) {
  if (/^(https?:\/\/|wxfile:\/\/|cloud:\/\/|\/static\/|\/assets\/)/i.test(tempPath)) {
    return tempPath;
  }
  return `wxfile://${tempPath}`;
}

function errorMessage(error: unknown) {
  if (typeof error === "object" && error && "errMsg" in error) {
    return String((error as { errMsg?: unknown }).errMsg || "");
  }
  if (error instanceof Error) {
    return error.message;
  }
  return String(error || "");
}

async function uploadToCos(tempPath: string, maxSizeBytes: number) {
  const fileName = tempPath.split("/").pop() || `upload-${Date.now()}.jpg`;
  const token = await getGenericUploadToken({ fileName, maxSizeBytes });
  if (token.url.startsWith("https://mock-cos")) {
    return normalizeLocalImagePath(tempPath);
  }

  const fs = uni.getFileSystemManager();
  const data = fs.readFileSync(tempPath);
  const res = (await uni.request({
    url: token.url,
    method: "PUT",
    data,
    header: { "Content-Type": "" },
  })) as UniApp.RequestSuccessCallbackResult;
  if (res.statusCode < 200 || res.statusCode >= 300) {
    throw new Error(`COS upload failed: ${res.statusCode}`);
  }
  return token.url.split("?")[0];
}

export async function uploadAvatar(tempPath: string) {
  return uploadToCos(tempPath, 2 * 1024 * 1024);
}

export async function uploadServiceImage() {
  try {
    const res = await uni.chooseImage({ count: 1, sizeType: ["compressed"] });
    const tempPath = res.tempFilePaths?.[0];
    if (!tempPath) return null;

    const tempFile = Array.isArray(res.tempFiles) ? res.tempFiles[0] : res.tempFiles;
    const size = tempFile?.size;
    const maxSizeBytes = 3 * 1024 * 1024;
    if (typeof size === "number" && size > maxSizeBytes) {
      toast("图片不能超过 3MB");
      return null;
    }

    return await uploadToCos(tempPath, maxSizeBytes);
  } catch (error) {
    const message = errorMessage(error);
    if (/cancel/i.test(message)) {
      return null;
    }
    toast("图片上传失败，请重试");
    return null;
  }
}
