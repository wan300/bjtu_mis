import { BizError } from "@/api/request";

export async function pendingApi<T>(message = "后端接口待接入"): Promise<T> {
  throw new BizError(90002, message);
}
