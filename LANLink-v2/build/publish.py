# -*- coding: utf-8 -*-
"""
LANLink 发布到 GitHub —— 一键脚本。

用法（三种任选）：
  1) python build/publish.py --token <PAT> --repo <仓库名>
  2) python build/publish.py --token <PAT> --repo <仓库名> --owner <用户名>
  3) 设置环境变量 GITHUB_TOKEN 后：python build/publish.py --repo <仓库名>

不修改 hosts、不清系统代理 —— 通过真实 IP 直连（见 gh_client.py）。
"""
import argparse
import hashlib
import io
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gh_client as gh

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASE = os.path.join(ROOT, 'release')

# 磁盘上的 zip 文件名 → GitHub 附件名（规范名，不带时间戳后缀）
# 打包脚本因「安全删除」限制会生成带时间戳的目录/文件名，
# 发布时统一改回规范名，避免 Releases 页面出现一堆奇怪文件名。
def _asset_name(fname):
    """LANLink-v2.4.0-win64-1026-150124.zip → LANLink-v2.4.0-win64.zip"""
    base = fname[:-4] if fname.endswith('.zip') else fname
    parts = base.split('-')
    while len(parts) >= 3 and len(parts[-1]) == 6 and parts[-1].isdigit() \
            and len(parts[-2]) == 4 and parts[-2].isdigit():
        parts = parts[:-2]
    out = '-'.join(parts) + '.zip'
    return out

# 五个版本：标签、文件名、标题、是否为推荐版本
VERSIONS = [
    ('v2.0.0', 'LANLink-v2-win64.zip',
     'v2.0.0 · 首个虚拟局域网版本', False,
     '首个可用的虚拟局域网版本。\n\n'
     '- 桌面端 exe（含 Java 运行环境，无需另行安装）\n'
     '- 手机端 APK（EasyTier 官方 ARM64 版）\n'
     '- 内置 EasyTier v2.6.4 内核（Apache-2.0）'),

    ('v2.1.0', 'LANLink-v2.1.0-win64.zip',
     'v2.1.0 · 密钥重构与稳定性改进', False,
     '安全与稳定性的一版大改。\n\n'
     '- 房间密钥由 6 位提升至 16 位随机字符（7.2×10²³ 组合）\n'
     '- 网络名称固定为 `lanlink`，房间隔离完全由密钥承担\n'
     '- 监听端口每次建房随机选取（20000-60000），不再固定暴露 11010\n'
     '- 启动前预检：内核文件、wintun 驱动、管理员权限\n'
     '- 防火墙入站规则自动添加\n'
     '- 掉线提示、公网 IP 漂移检测、端口占用预探测\n'
     '- 虚拟 IP 由 `sha256(密钥+本机指纹)` 派生，重连地址不变\n\n'
     '**破坏性变更**：旧版 6 位密码的邀请码不再可用，需重新建房。'),

    ('v2.2.0', 'LANLink-v2.2.0-win64.zip',
     'v2.2.0 · 网络环境诊断与中继兜底', False,
     '解决「显示已连接但没有成员列表」的问题。\n\n'
     '- 自动检测 NAT 类型（`easytier-cli stun`）与 UPnP 端口映射状态\n'
     '- 新增【复制诊断信息】按钮，一键导出排查所需信息\n'
     '- 可填写共享中继节点，直连失败时经其转发\n'
     '- 修正加入方状态误报：内核启动成功不再等同于链路建立，'
     '现在真正看到主机 `10.144.0.1` 才显示「已连接」'),

    ('v2.3.0', 'LANLink-v2.3.0-win64.zip',
     'v2.3.0 · 内置社区公共节点（免注册、免费）', False,
     '面向无路由器、无服务器的实际使用场景。\n\n'
     '- 内置社区公开的共享节点，直连失败时自动经其中继\n'
     '- 直连优先（延迟约 1 毫秒），打不通自动走中继（约 100-400 毫秒）\n'
     '- 无需注册账号、无需自备服务器、无需改动路由器设置\n'
     '- 公共节点在成员列表中自动过滤，不污染成员显示'),

    ('v2.5.0', 'LANLink-v2.5.0-win64.zip',
     'v2.5.0 · 异地联机实测通过', False,
     '异地联机（双方不在同一网络）经社区公共节点中继实测打通。\n\n'
     '- 新增「强制只用中继」开关（环境变量 `LANLINK_NO_P2P=1`），\n'
     '  在对称型 NAT 且端口未映射的网络里可更快建立连接\n'
     '- 新增真实数据夹具测试：用内核在强制中继模式下的真实输出验证成员列表解析\n'
     '- 新增中继端到端测试：起两个真实内核、禁用打洞，验证双方能经公共节点互相发现\n'
     '- 上述两项测试已接入构建流水线，失败即中止打包'),

    ('v2.4.0', 'LANLink-v2.4.0-win64.zip',
     'v2.4.0 · 修复启动即崩', False,
     '修复界面控件未赋值导致程序启动即退的问题。\n\n'
     '- 修复界面控件未赋值导致程序启动即退的问题\n'
     '- 架构调整：界面内容与窗口壳分离，界面可在无头环境完整构建与自动测试\n'
     '- 新增界面冒烟测试：遍历组件树检查空组件、空标题、无文字按钮\n'
     '- 新增静态检查：编译期拦截「声明却未赋值」的界面字段\n'
     '- 启动失败时给出含日志路径的明确提示，不再静默退出\n\n'
     '同时内置 `LANLink.exe --uitest` 自检模式，可验证界面是否正常构建。'),

    ('v2.6.0', 'LANLink-v2.6.0-win64.zip',
     'v2.6.0 · 手机端配置修复与界面自适应', True,
     '**当前推荐版本。**\n\n'
     '## 修复手机端「运行中但互相看不到成员」\n\n'
     '根因是导出的配置只有公网地址一条路径：\n'
     '- 同一路由器下本可零延迟直连，却绕道公网被 NAT 拦下；\n'
     '- 未包含公共节点，而中继要求**双方都挂在公共节点网络上**。\n\n'
     '现同时给出三条路径：局域网地址、公网地址、社区公共节点。\n'
     '内核级实测：加入方使用新配置后 **3 秒内互相发现**。\n\n'
     '## 界面自适应\n\n'
     '- 窗口尺寸按屏幕可用区域计算，标题栏与关闭按钮不再跑到屏幕外\n'
     '- 界面内容超高时可滚动，不再被裁掉\n\n'
     '## 工程保障\n\n'
     '- 构建流水线共五道关卡：静态字段检查、2128 项逻辑自检、'
     '界面冒烟、真实数据夹具与中继端到端、界面布局检查\n\n'
     '**破坏性变更**：请重新建房并重新生成配置文件。'),
]

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
        m = re.match(r'^LANLink-v(\d+(?:\.\d+)*)-win64(?:-\d+-\d+)?\.zip$', f)
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
    taken_fnames = set(item[1] for item in known.values())
    for ver, path in scanned.items():
        if ver in known:
            continue
        fname = os.path.basename(path)
        # 同一个包名已被硬编码列表占用（如 v2.0.0 的 LANLink-v2-win64.zip
        # 会被版本号正则误读成 "2"），跳过以免产生重复版本
        if fname in taken_fnames:
            continue
        # 新版本：自动补入。标题与说明由 RELEASE_NOTES 提供或自动生成。
        notes = RELEASE_NOTES.get('v' + ver, '')
        if not notes:
            notes = '本版本发布说明见项目变更记录。'
        title = 'v%s · 新版本' % ver
        known[ver] = ['v' + ver, fname, title, False, notes]
        taken_fnames.add(fname)
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

    # 推荐版只能是最新的一版：把旧版本上的推荐标记降级
    # 避免出现两个版本都标「推荐」而让用户无所适从。
    latest = result[0][0] if result else None
    for item in result:
        if item[3] and item[0] != latest:
            item[3] = False
    if latest:
        for item in result:
            if item[0] == latest:
                item[3] = True

    return result, missing


def find_zip(tag, wanted):
    """按标签找磁盘上的 zip，容忍打包时加的时间戳后缀。

    优先精确匹配 LANLink-v2.4.0-win64.zip；
    否则找 LANLink-v2.4.0-win64-*.zip 中最新的一个。
    """
    exact = os.path.join(RELEASE, wanted)
    if os.path.exists(exact):
        return exact
    prefix = wanted[:-4] + '-'
    cands = []
    for f in os.listdir(RELEASE):
        if f.startswith(prefix) and f.endswith('.zip'):
            p = os.path.join(RELEASE, f)
            cands.append((os.path.getmtime(p), p))
    if not cands:
        return None
    cands.sort()
    return cands[-1][1]


def log(m):
    print(m, flush=True)


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def read_text(name):
    p = os.path.join(ROOT, name)
    return io.open(p, encoding='utf-8').read() if os.path.exists(p) else ''


RELEASE_NOTES = {
    'v2.0.0': '首个虚拟局域网版本，界面为英文，未接入中继兜底。',
    'v2.1.0': '密钥由 6 位提升至 16 位随机字符；端口随机化；加入启动预检、防火墙规则与掉线提示。',
    'v2.2.0': '新增 NAT 类型与端口映射诊断、共享中继节点填写、诊断信息导出；修正加入方「已连接」误报。',
    'v2.3.0': '内置社区公共节点作为中继兜底，无需注册账号与自备服务器。',
    'v2.4.0': '修复启动即崩；界面层可被自动化测试覆盖。',
    'v2.5.0': '异地联机经社区公共节点中继实测打通；新增夹具测试与中继端到端测试。',
    'v2.6.0': '**推荐使用此版本。**\n\n- 修复手机端导入配置后「运行中但互相看不到成员」的问题：\n  配置现同时给出局域网地址、公网地址与社区公共节点三条路径\n- 修复窗口尺寸写死导致标题栏与关闭按钮跑到屏幕外的问题\n- 界面内容超高时可滚动，不再被裁掉\n- 构建流水线新增界面布局检查，共五道关卡\n\n  本版本与旧版不兼容，请重新建房并重新生成配置文件。',
}


def ensure_repo(token, name, description, public=True):
    """仓库不存在则创建，返回 (owner, repo)"""
    user = gh.whoami(token)
    owner = user['login']
    full = '%s/%s' % (owner, name)
    log('账号: %s (%s)' % (owner, user.get('name') or '未设昵称'))
    log('目标仓库: %s' % full)

    try:
        gh.api_get('/repos/' + full, token=token)
        log('仓库已存在，将直接使用')
        return owner, name
    except gh.GitHubError as e:
        if e.status != 404:
            raise

    payload = {
        'name': name,
        'description': description,
        'private': not public,
        'auto_init': False,
        'has_issues': True,
        'has_wiki': False,
        'has_projects': False,
    }
    log('创建仓库…')
    gh.api_send('POST', '/user/repos', token=token, payload=payload, expect=(201,))
    log('已创建')
    return owner, name


def ensure_branch(token, owner, repo):
    """空仓库需要先有分支，Release 才有 target_commitish 可指向。

    注意：GitHub 的 Git Data API 在仓库完全为空（无任何 commit）时，
    POST /git/blobs 会返回 409 "Git Repository is empty"。
    解决办法：先 PUT /contents/README.md 建首个 commit（contents API 会
    自动创建 default_branch），再确认分支存在。
    """
    try:
        gh.api_get('/repos/%s/%s/branches/main' % (owner, repo), token=token)
        log('  main 分支已存在')
        return
    except gh.GitHubError as e:
        if e.status != 404:
            raise

    # 用 contents API 建首个 commit（会自动初始化 default_branch）
    import base64
    payload = {
        'message': 'chore: 初始化仓库',
        'content': base64.b64encode(('# %s\n' % repo).encode('utf-8')).decode('ascii'),
        'branch': 'main',
    }
    gh.api_send('PUT', '/repos/%s/%s/contents/.gitkeep' % (owner, repo),
                token=token, payload=payload, expect=(201,))
    log('  已创建 main 分支')

    # default_branch 可能不是 main，按仓库实际设置对齐
    try:
        info = gh.api_get('/repos/%s/%s' % (owner, repo), token=token)
        if info.get('default_branch') and info['default_branch'] != 'main':
            gh.api_send('PATCH', '/repos/%s/%s' % (owner, repo),
                        token=token,
                        payload={'default_branch': 'main'}, expect=(200,))
            log('  默认分支已设为 main')
    except Exception:
        pass


def push_file(token, owner, repo, path, content, message):
    """用 Git Data API 写入文件（不依赖 git 客户端）"""
    import base64
    try:
        existing = gh.api_get('/repos/%s/%s/contents/%s' % (owner, repo, path), token=token)
        sha = existing.get('sha')
    except gh.GitHubError:
        sha = None

    payload = {
        'message': message,
        'content': base64.b64encode(content.encode('utf-8')).decode('ascii'),
        'branch': 'main',
    }
    if sha:
        payload['sha'] = sha
        gh.api_send('PUT', '/repos/%s/%s/contents/%s' % (owner, repo, path),
                    token=token, payload=payload, expect=(200, 201))
        log('  已更新 %s' % path)
    else:
        gh.api_send('PUT', '/repos/%s/%s/contents/%s' % (owner, repo, path),
                    token=token, payload=payload, expect=(201,))
        log('  已创建 %s' % path)


def create_release(token, owner, repo, tag, name, notes, prerelease=False, draft=False):
    payload = {
        'tag_name': tag,
        'name': name,
        'body': notes,
        'prerelease': prerelease,
        'draft': draft,
        'target_commitish': 'main',
    }
    try:
        r = gh.api_send('POST', '/repos/%s/%s/releases' % (owner, repo),
                        token=token, payload=payload, expect=(201,))
        log('  已创建 Release %s' % tag)
        return r
    except gh.GitHubError as e:
        if e.status == 422:
            # 已存在则取回
            rels = gh.api_get('/repos/%s/%s/releases' % (owner, repo), token=token)
            for rel in rels:
                if rel.get('tag_name') == tag:
                    log('  Release %s 已存在，跳过创建' % tag)
                    return rel
        raise


def attach_asset(token, owner, repo, release_id, filepath, asset_name=None):
    name = asset_name or os.path.basename(filepath)
    size = os.path.getsize(filepath)
    # 先清掉同名残留资产。
    # 上一次上传若中途失败（网络超时），GitHub 侧会留下半截资产，
    # 导致后续申请上传地址返回 422、覆盖上传返回 400 Bad request。
    try:
        rel = gh.api_get('/repos/%s/%s/releases/%d' % (owner, repo, release_id),
                         token=token)
        for a in rel.get('assets', []):
            if a['name'] == name:
                gh.api_send('DELETE',
                            '/repos/%s/%s/releases/assets/%d' % (owner, repo, a['id']),
                            token=token, expect=(204,))
                log('  已清理同名残留资产 %s' % name)
    except Exception as e:
        log('  （清理同名资产时出错：%s）' % str(e)[:60])

    log('  上传 %s (%.1f MB) …' % (name, size / 1048576))
    t = time.time()
    url = ('https://uploads.github.com/repos/%s/%s/releases/%d/assets?name=%s'
           % (owner, repo, release_id, name))
    gh.upload_asset(url, token, filepath, upload_name=name, timeout=3600)
    dt = time.time() - t
    speed = size / 1048576 / max(dt, 0.001)
    log('  完成（用时 %.0fs，约 %.1f MB/s）' % (dt, speed))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--token', default=os.environ.get('GITHUB_TOKEN'))
    ap.add_argument('--repo', default='LANLink')
    ap.add_argument('--owner', default='')
    ap.add_argument('--private', action='store_true')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    if not args.token:
        log('!! 缺少 token。用 --token <PAT> 或设置环境变量 GITHUB_TOKEN')
        return 1

    log('=== 1. 连通性与权限自检 ===')
    rows = gh.check(args.token)
    for name, st, detail in rows:
        log('  [%s] %-10s %s' % (st, name, detail))
        if st == 'FAIL' and not args.dry_run:
            log('!! 自检未通过，终止')
            return 1
    if args.dry_run and any(st == 'FAIL' for _, st, _ in rows):
        log('  (dry-run 模式：token 无效也继续演练后续流程)')

    owner = args.owner
    if not owner:
        try:
            owner = gh.whoami(args.token)['login']
        except Exception:
            owner = '<你的用户名>'
            if not args.dry_run:
                log('!! 无法读取账号名，请用 --owner 指定')
                return 1

    log('')
    log('=== 2. 准备仓库 ===')
    desc = ('LANLink 联机助手 —— 无需注册、无需自备服务器的虚拟局域网工具，'
            '支持异地设备组建同一虚拟局域网并互相使用对方虚拟 IP 联机游戏。')
    full = '%s/%s' % (owner, args.repo)

    if args.dry_run:
        log('  [dry-run] 将创建/使用仓库 %s（%s）' % (full, '私有' if args.private else '公开'))
    else:
        try:
            gh.api_get('/repos/' + full, token=args.token)
            log('  仓库已存在：%s' % full)
        except gh.GitHubError as e:
            if e.status != 404:
                raise
            payload = {
                'name': args.repo,
                'description': desc,
                'private': args.private,
                'auto_init': False,
                'has_issues': True,
                'has_wiki': False,
            }
            gh.api_send('POST', '/user/repos', token=args.token,
                        payload=payload, expect=(201,))
            log('  已创建仓库 %s' % full)

    readme = read_text('README.md')
    if not readme:
        readme = '# LANLink 联机助手\n\n见 Releases 页面。\n'

    # 顺序很重要：必须先有分支和内容，Release 的 target_commitish 才有指向。
    # 空仓库直接建 Release 会返回 422 Validation Failed。
    log('')
    log('=== 3. 写入 README 与 LICENSE ===')
    if not args.dry_run:
        ensure_branch(args.token, owner, args.repo)
        push_file(args.token, owner, args.repo, 'README.md', readme,
                  'docs: 添加项目说明')
        license_text = read_text('LICENSE')
        if license_text:
            push_file(args.token, owner, args.repo, 'LICENSE', license_text,
                      'docs: 添加许可声明与第三方组件声明')
    else:
        log('  [dry-run] 将创建 main 分支并写入 README.md、LICENSE')

    log('')
    log('=== 4. 发布各版本 ===')
    summary = []
    version_list, missing = build_version_list()
    if missing:
        log('以下版本在 release 目录中找不到包，将跳过：' + ', '.join(missing))
    if not version_list:
        log('!! 未找到任何可发布版本')
        return 1
    for tag, fname, title, recommended, notes in version_list:
        path = find_zip(tag, fname)
        if not path:
            log('  跳过 %s（找不到 %s）' % (tag, fname))
            continue
        asset = _asset_name(os.path.basename(path))

        log('')
        log('--- %s ---' % tag)
        if args.dry_run:
            log('  [dry-run] 将创建 Release 并上传 %s（%.1f MB，磁盘名 %s）'
                % (asset, os.path.getsize(path) / 1048576, os.path.basename(path)))
            summary.append((tag, asset, recommended))
            continue

        try:
            rel = create_release(args.token, owner, args.repo, tag, title,
                                 notes + '\n\n' + RELEASE_NOTES.get(tag, ''))
        except gh.GitHubError as e:
            log('  !! 创建 Release 失败：%s' % e)
            continue

        rel_id = rel['id']
        # 检查附件是否已存在
        existing = {a['name'] for a in rel.get('assets', [])}
        if asset in existing:
            log('  附件已存在，跳过上传')
        else:
            attach_asset(args.token, owner, args.repo, rel_id, path, asset_name=asset)

        # 附上校验值
        digest = sha256(path)
        try:
            existing = {a['name'] for a in rel.get('assets', [])}
            sha_name = asset + '.sha256'
            if sha_name not in existing:
                content = '%s  %s\n' % (digest, asset)
                url = ('https://uploads.github.com/repos/%s/%s/releases/%d/assets?name=%s'
                       % (owner, args.repo, rel_id, sha_name))
                gh.upload_asset(url, args.token,
                                _write_temp(content, sha_name),
                                upload_name=sha_name, timeout=120)
                log('  + 校验值文件 %s' % sha_name)
        except Exception as e:
            log('  （校验值文件上传失败：%s）' % str(e)[:60])

        summary.append((tag, asset, recommended))

    log('')
    log('=== 完成 ===')
    for tag, fname, rec in summary:
        log('  %s %-28s %s' % ('★' if rec else ' ', tag, fname))
    log('')
    log('仓库地址: https://github.com/%s/%s' % (owner, args.repo))
    log('发布页面: https://github.com/%s/%s/releases' % (owner, args.repo))
    return 0


def _write_temp(content, name):
    import tempfile
    p = os.path.join(tempfile.gettempdir(), name)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(content)
    return p


if __name__ == '__main__':
    sys.exit(main())
