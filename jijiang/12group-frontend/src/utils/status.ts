export const ORDER_STATUS: Record<number, { text: string; tone: "idle" | "warn" | "active" | "done" | "danger" }> = {
  10: { text: "待支付", tone: "warn" },
  20: { text: "待接单", tone: "active" },
  30: { text: "进行中", tone: "active" },
  40: { text: "待确认", tone: "warn" },
  50: { text: "已完成", tone: "done" },
  60: { text: "已关闭", tone: "idle" },
  70: { text: "退款中", tone: "danger" },
  80: { text: "已退款", tone: "idle" },
};

export const WITHDRAW_STATUS: Record<number, { text: string; tone: "idle" | "warn" | "active" | "done" | "danger" }> = {
  0: { text: "待审核", tone: "warn" },
  1: { text: "待打款", tone: "active" },
  2: { text: "已到账", tone: "done" },
  3: { text: "已驳回", tone: "idle" },
};

export function orderStatusText(status?: number) {
  return ORDER_STATUS[Number(status)]?.text || "未知状态";
}

export function withdrawStatusText(status?: number) {
  return WITHDRAW_STATUS[Number(status)]?.text || "未知状态";
}

export function withdrawStatusTone(status?: number) {
  return WITHDRAW_STATUS[Number(status)]?.tone || "idle";
}

export function creditLevel(score?: number) {
  const value = Number(score || 100);
  if (value >= 110) return "A+ 优质讲师";
  if (value >= 90) return "A 稳定可信";
  if (value >= 80) return "B 正常";
  if (value >= 60) return "C 需观察";
  return "D 受限";
}

export const SERVICE_STATUS: Record<number, { text: string; tone: "idle" | "warn" | "active" | "done" | "danger" }> = {
  0: { text: "待审核", tone: "warn" },
  1: { text: "已上架", tone: "done" },
  2: { text: "已下架", tone: "idle" },
};

export function serviceStatusText(status?: number) {
  return SERVICE_STATUS[Number(status)]?.text || "未知状态";
}

export function serviceStatusTone(status?: number) {
  return SERVICE_STATUS[Number(status)]?.tone || "idle";
}
