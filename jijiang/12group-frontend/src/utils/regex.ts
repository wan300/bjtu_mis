export const SENSITIVE_CONTACT_REGEX =
  /(微信|vx|v信|qq|QQ|电话|手机号|加我|联系我|[1][3-9]\d{9}|\d{6,})/i;

export const RISK_SERVICE_REGEX = /(代写|包过|保过|替考|作弊|论文代)/i;

export function hitSensitiveContact(text: string) {
  return SENSITIVE_CONTACT_REGEX.test(text);
}

export function hitRiskService(text: string) {
  return RISK_SERVICE_REGEX.test(text);
}
