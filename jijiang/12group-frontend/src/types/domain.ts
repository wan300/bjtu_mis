export type Role = 1 | 2;

export interface UserInfo {
  id: number;
  nickname: string;
  avatarUrl?: string;
  verifyStatus: number;
  currentRole: Role;
  campusId: number;
  campusName?: string;
  creditScore: number;
  isSellerVerified: number;
  depositPaid: number;
  hasWechatIdentity?: boolean;
  hasMisIdentity?: boolean;
}

export interface LoginResult {
  accessToken: string;
  refreshToken: string;
  userInfo: UserInfo;
}

export type MisLoginStatus = "ready" | "manual_required" | "auto_failed";

export interface MisCaptcha {
  challengeId: string;
  imageDataUrl: string;
  fetchedAt: string;
}

export interface MisLoginResult {
  status: MisLoginStatus;
  message?: string;
  attempts: number;
  captcha?: MisCaptcha;
  accessToken?: string;
  refreshToken?: string;
  userInfo?: UserInfo;
}

export interface Category {
  id: number;
  name: string;
  icon?: string;
}

export interface ServiceItem {
  id: number;
  sellerId: number;
  sellerName?: string;
  categoryId: number;
  categoryName?: string;
  title: string;
  description: string;
  price: number;
  priceConfig?: string;
  coverUrl?: string;
  stock: number;
  usedStock: number;
  status?: number;
  scoreAvg?: number;
  salesCount?: number;
  publishTime?: string;
  createTime?: string;
  sellerCredit?: number;
}

export interface ServicePriceTier {
  key: string;
  name: string;
  price: number;
  unit: string;
  qty: number;
}

export interface DemandItem {
  id: number;
  buyerId: number;
  buyerName?: string;
  campusId: number;
  categoryId: number;
  categoryName?: string;
  title: string;
  description: string;
  budgetAmount: number;
  expectedTime?: string;
  status: number;
  bidCount: number;
  createTime?: string;
}

export interface DemandBid {
  id: number;
  demandId: number;
  sellerId: number;
  sellerName?: string;
  sellerAvatarUrl?: string;
  sellerCreditScore?: number;
  price: number;
  proposal: string;
  serviceTime?: string;
  status: number;
  createTime?: string;
  updateTime?: string;
}

export interface OrderItem {
  id: number;
  orderId?: number;
  orderNo: string;
  buyerId: number;
  buyerName?: string;
  sellerId: number;
  sellerName?: string;
  serviceId: number;
  serviceTitle?: string;
  serviceCoverUrl?: string;
  amount: number;
  status: number;
  remark?: string;
  deliverText?: string;
  createTime?: string;
  payTime?: string;
  acceptTime?: string;
  deliverTime?: string;
  confirmTime?: string;
  expireTime?: string;
  serverTime?: string;
  remainingSeconds?: number;
  priceTierKey?: string;
  priceTierName?: string;
  priceTierQty?: number;
  priceTierUnit?: string;
}

export interface OrderCreateResult {
  orderId: number;
  orderNo: string;
  amount: number;
  status: number;
}

export interface MessageItem {
  id: number;
  orderId: number;
  senderId: number;
  senderName?: string;
  senderAvatar?: string;
  receiverId: number;
  content: string;
  isRead: number;
  createTime: string;
}

export interface VerifyResult {
  recordId: number;
  status: number;
  reviewMode: number;
  message: string;
}

export interface PaymentCreateResult {
  channel: "XUNHUPAY";
  orderId: number;
  orderNo: string;
  tradeOrderId: string;
  payUrl: string;
  qrCodeUrl?: string;
  expireSeconds: number;
}

export interface UploadToken {
  url: string;
  fields: Record<string, string>;
  fileKey: string;
  expiresAt: number;
}

export interface DepositRecord {
  id: number;
  amount: number;
  status: number;
  depositType: string;
  outTradeNo?: string;
  transactionId?: string;
  payTime?: string;
  refundTime?: string;
  createTime?: string;
}

export interface DepositStatus {
  depositPaid: number;
  records: DepositRecord[];
}

export interface DepositCreateResult {
  recordId: number | null;
  amount: number;
  status: number;
  outTradeNo?: string;
  message: string;
  paid: boolean;
  channel?: "XUNHUPAY";
  tradeOrderId?: string;
  payUrl?: string;
  qrCodeUrl?: string;
  expireSeconds?: number;
}

export interface DepositSyncResult {
  depositPaid: number;
  recordId: number;
  outTradeNo: string;
  paid: boolean;
  paymentStatus: "PENDING" | "SUCCESS" | string;
  status: number;
  payTime?: string;
}

export interface DeletionStatus {
  status: number;
  requestTime?: string;
  coolingUntil?: string;
  completedTime?: string;
  cancelledTime?: string;
  deletionStatus?: number;
  message?: string;
}

export interface RefundSubmitRequest {
  orderId: number;
  reason: string;
  evidenceUrls?: string[];
}

export interface RefundRequestItem {
  id: number;
  orderId: number;
  userId: number;
  sellerId: number;
  reason: string;
  evidenceUrls?: string[];
  amount: number;
  status: number;
  reviewerId?: number;
  reviewRemark?: string;
  reviewTime?: string;
  deductDeposit: number;
  createTime?: string;
  orderNo?: string;
  orderAmount?: number;
  orderStatus?: number;
}

export interface ReviewStats {
  total: number;
  avg: number;
  distribution: Record<number, number>;
  recommendRate: number;
  completionRate: number;
  tagStats: Record<string, number>;
}

export interface ReviewItem {
  id: number;
  orderId: number;
  serviceId: number;
  reviewerId: number;
  reviewerName: string;
  reviewerAvatar: string;
  score: number;
  content: string;
  tags: string[];
  isAnonymous: boolean;
  images: string[];
  replyContent?: string;
  replyTime?: string;
  followUpContent?: string;
  followUpImages?: string[];
  followUpTime?: string;
  followUpReplyContent?: string;
  followUpReplyTime?: string;
  createTime: string;
}

export interface PaymentSyncResult {
  orderId: number;
  orderNo: string;
  status: number;
  paid: boolean;
  paymentStatus: "PENDING" | "SUCCESS" | string;
  tradeOrderId?: string;
  paymentRecordStatus?: number;
  payTime?: string;
}

export interface WithdrawalBalance {
  balance: number | string;
  frozenBalance: number | string;
  pendingWithdraw: number | string;
  totalIncome: number | string;
  withdrawnTotal: number | string;
}

export interface WithdrawalRecord {
  id: number;
  userId: number;
  amount: number | string;
  status: number;
  payeeInfo?: string;
  outTradeNo?: string;
  transactionId?: string;
  reviewerId?: number;
  reviewRemark?: string;
  reviewTime?: string;
  reviewScreenshot?: string;
  completeTime?: string;
  createTime?: string;
  updateTime?: string;
}

export interface WithdrawApplyRequest {
  amount: number;
  payeeInfo: string;
}

export interface WithdrawApplyResult {
  id: number;
  amount: number | string;
  status: number;
}
