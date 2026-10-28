/**
 * 品牌单一配置源。
 *
 * 改名只需动这个文件 + package.json 的 productName，其余代码一律引用这里，
 * 避免品牌名散落在几十个组件里。
 *
 * 注意一个刻意的例外：
 *   PROTOCOL.networkName 必须保持 'lanlink' —— 它是与 EasyTier 内核、
 *   以及已导入配置的手机端之间的协议标识，改了会让老配置全部失联。
 *   产品名与协议标识是两件事。
 */
export const BRAND = {
  name: 'Sparsa',
  nameZh: '零星',
  appId: 'com.sparsa.desktop',

  // 品牌层只保留英文短句。中文口号已按文案规范删除。
  taglineEn: 'Scattered devices, one network.',

  // 文件与仓库
  configFileName: 'sparsa.toml',
  repo: 'https://github.com/jywzzx/Sparsa',

  // 子模块命名
  modules: {
    desk: 'Sparsa Desk',
    link: 'Sparsa Link',
    mobile: 'Sparsa Mobile'
  }
}

/** 协议层常量：不可随品牌改名 */
export const PROTOCOL = {
  /** EasyTier network_name，全端固定 */
  networkName: 'lanlink',
  /** 虚拟网段前缀 */
  vnetPrefix: '10.144.0.',
  /** 主机（房主）固定虚拟 IP */
  hostIp: '10.144.0.1'
}

/** 展示用组合名，例如「Sparsa 零星」 */
export const fullName = `${BRAND.name} ${BRAND.nameZh}`

export default BRAND
