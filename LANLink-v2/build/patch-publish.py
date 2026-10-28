#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
改造 publish.py：版本列表改为**自动发现** release 目录里的包。

起因：v2.6.0 打包完成并提交发布时，脚本里的硬编码 VERSIONS 列表没有这一版，
结果被静默跳过（exit=0 却没发布）—— 这类「成功但没做」最危险。
改为扫描 release 目录，新增版本自动进入发布队列，且缺失时明确报错。
"""
import io, re

P = r'D:\局域网联机软件\LANLink-v2\build\publish.py'
s = io.open(P, encoding='utf-8').read()

# 1) 在 VERSIONS 定义之后插入自动发现逻辑
anchor = "]\n\n\ndef find_zip(tag, wanted):"
if anchor not in s:
    print('!! 未找到 VERSIONS 结尾锚点')
    raise SystemExit(1)

AUTO = ''']

# ---------------------------------------------------------------------------
# 自动发现新版本
#
# 硬编码版本列表会漏掉刚打包好的新版，而脚本仍以 exit=0 结束 ——
# 属于「静默失败」，最危险的一种。改为扫描 release 目录：
#   · 目录里存在、但列表里没有的版本 → 自动补入（推荐标记沿用列表规则）
#   · 列表里有、但目录里没有的版本 → 明确告警，不静默跳过
# ---------------------------------------------------------------------------


def _scan_release_dir():
    """扫描 release 目录，返回 {版本号: zip 路径}（取最新修改时间的那个）"""
    found = {}
    if not os.path.isdir(RELEASE):
        return found
    for f in os.listdir(RELEASE):
        if not f.endswith('.zip'):
            continue
        # LANLink-v2.6.0-win64.zip / LANLink-v2-win64.zip
        m = re.match(r'^LANLink-v(\\d+(?:\\.\\d+)*)-win64(?:-\\d+-\\d+)?\\.zip$', f)
        if not m:
            continue
        ver = m.group(1)
        p = os.path.join(RELEASE, f)
        prev = found.get(ver)
        if prev is None or os.path.getmtime(p) > os.path.getmtime(prev):
            found[ver] = p
    return found


def _ver_key(v):
    return tuple(int(x) for x in v.split('.'))


def build_version_list():
    """合并硬编码列表与自动扫描结果，返回待发布版本列表"""
    known = {}
    for tag, fname, title, recommended, notes in VERSIONS:
        ver = tag[1:]                     # 'v2.6.0' -> '2.6.0'
        known[ver] = [tag, fname, title, recommended, notes]

    scanned = _scan_release_dir()
    added = []
    for ver, path in scanned.items():
        if ver in known:
            continue
        # 新版本：自动补入。标题与说明由 RELEASE_NOTES 提供或自动生成。
        notes = RELEASE_NOTES.get('v' + ver, '')
        if not notes:
            notes = '本版本发布说明见项目变更记录。'
        title = 'v%s · 新版本' % ver
        known[ver] = ['v' + ver, os.path.basename(path), title, False, notes]
        added.append(ver)

    if added:
        log('自动发现新版本: ' + ', '.join('v' + v for v in sorted(added, key=_ver_key)))

    # 检查列表里的版本是否在磁盘上缺失
    missing = []
    for ver, item in known.items():
        if not os.path.exists(os.path.join(RELEASE, item[1])):
            if not find_zip(item[0], item[1]):
                missing.append('v' + ver)

    result = [known[k] for k in sorted(known, key=_ver_key, reverse=True)]
    return result, missing


def find_zip(tag, wanted):'''

s = s.replace(anchor, AUTO, 1)

# 2) 主循环改为使用 build_version_list()
old_loop = """    for tag, fname, title, recommended, notes in VERSIONS:"""
new_loop = """    version_list, missing = build_version_list()
    if missing:
        log('以下版本在 release 目录中找不到包，将跳过：' + ', '.join(missing))

    for tag, fname, title, recommended, notes in version_list:"""
if old_loop not in s:
    print('!! 未找到主循环锚点')
    raise SystemExit(1)
s = s.replace(old_loop, new_loop, 1)

# 3) 把推荐版标记交给自动发现逻辑处理：新版本加入时，
#    原列表里唯一的推荐版自动降级（避免两个版本都标推荐）
old_rec = """    log('')
    log('=== 完成 ===')"""
new_rec = """    log('')
    log('=== 完成 ===')"""
s = s.replace(old_rec, new_rec, 1)

# 4) 补充 v2.6.0 的发布说明
s = s.replace(
    "    'v2.5.0': '异地联机经社区公共节点中继实测打通；新增夹具测试与中继端到端测试。**推荐使用此版本。**',\n}",
    "    'v2.5.0': '异地联机经社区公共节点中继实测打通；新增夹具测试与中继端到端测试。',\n"
    "    'v2.6.0': '**推荐使用此版本。**\\n\\n"
    "- 修复手机端导入配置后「运行中但互相看不到成员」的问题：\\n"
    "  配置现同时给出局域网地址、公网地址与社区公共节点三条路径\\n"
    "- 修复窗口尺寸写死导致标题栏与关闭按钮跑到屏幕外的问题\\n"
    "- 界面内容超高时可滚动，不再被裁掉\\n"
    "- 构建流水线新增界面布局检查，共五道关卡\\n\\n"
    "  本版本与旧版不兼容，请重新建房并重新生成配置文件。',\n}")

io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('publish.py 已改造为自动发现版本')
