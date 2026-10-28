/**
 * 二维码容量估算。
 *
 * 为什么要自己算：QR 的「2953 字节」上限只对**纯字节模式**成立。
 * 手机端要扫的是中英混排的配置文本，UTF-8 下每个汉字占 3 字节，
 * 直接拿字符数比上限会得出「能塞 2953 字」的错误结论，实际早就画不出来。
 *
 * 保守成本模型：ASCII 计 1，其余计 3。
 */

/** 成本上限。留足余量的原因：屏幕反光、手机摄像头分辨率、低端机解码能力。 */
export const SAFE_COST = 1400

export function qrCost(text) {
  let c = 0
  for (const ch of String(text || '')) {
    c += ch.codePointAt(0) < 128 ? 1 : 3
  }
  return c
}

/** 成本越低，纠错等级越高（屏幕上有反光也扫得出来） */
export function pickQrLevel(cost) {
  if (cost <= 260) return 'H'
  if (cost <= 620) return 'M'
  return 'L'
}

export function qrFits(text) {
  const c = qrCost(text)
  return c > 0 && c <= SAFE_COST
}
