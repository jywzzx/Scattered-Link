# -*- coding: utf-8 -*-
"""
一次性补发布 v2.6.1 / v2.6.2 / v2.6.3。

为什么单独写一个脚本而不直接跑 publish.py：
  1) publish.py 的 VERSIONS 是硬编码的，2.6.1~2.6.3 靠「自动扫描」补入时
     标题只会得到 'vX · 新版本'、说明是占位文本，不适合直接面向用户；
  2) 这里需要「只发缺失的版本」，不能重跑全量（每版 72 MB，重复上传浪费时间）；
  3) 需要按 mtime 取最新构建、并统一改名为规范名（去掉时间戳后缀）。

用法：python build/publish-26x.py [--dry-run]
"""
import hashlib
import io
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gh_client as gh

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RELEASE = os.path.join(ROOT, 'release')
OWNER, REPO = 'jywzzx', 'Sparsa'
TOKEN_FILE = os.path.join(ROOT, 'build', '_tok.tmp')


def log(m):
    print(m, flush=True)


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for c in iter(lambda: f.read(1024 * 1024), b''):
            h.update(c)
    return h.hexdigest()


def pick(ver):
    """取该版本最新构建；优先用规范名（无时间戳）的那一个"""
    exact = os.path.join(RELEASE, 'LANLink-v%s-win64.zip' % ver)
    if os.path.exists(exact):
        return exact
    cands = [os.path.join(RELEASE, f) for f in os.listdir(RELEASE)
             if f.startswith('LANLink-v%s-win64-' % ver) and f.endswith('.zip')]
    if not cands:
        return None
    cands.sort(key=os.path.getmtime)
    return cands[-1]


# 每版：标签 / 标题 / 是否推荐 / 正文
PLAN = [
    ('v2.6.1', 'v2.6.1 · 手机端配置跟随模式', False, """\
## 手机端配置不再「一套走天下」

强制统一中继模式下导出的配置，之前仍然会带上多个节点与打洞开关，
导致手机端和电脑端走的根本不是同一条路。现在配置严格跟随模式：

- **直连优先**：写入局域网地址、公网地址、社区公共节点三条路径
- **强制统一中继**：只写一个 `[[peer]]`，同时置
  `disable_p2p` / `disable_udp_hole_punching` / `disable_tcp_hole_punching`
  与 `latency_first`

## 提示文字从 TOML 里搬到了界面上

导入配置的人**不会打开文件看注释**，所以配置文件里的说明注释没有意义。
导出改为纯 TOML（零注释），所有提示改到桌面端「手机端配置」卡片上展示。

## 其他

- 网络密钥输入框不再泄露明文
- 构建流水线共六道关卡：静态字段检查、2100+ 项逻辑自检、界面冒烟、
  真实数据夹具、中继端到端、界面布局检查
"""),

    ('v2.6.2', 'v2.6.2 · 二维码扫码与文本粘贴导入', False, """\
## 新增三种配置搬运方式（全部免费，无任何付费依赖）

| 方式 | 做法 | 适合场景 |
|---|---|---|
| 扫码 | 电脑显示二维码 → 手机扫码 → 全选复制 | 手机与电脑在一起 |
| 文本 | 点「复制配置内容」→ 微信发给自己 → 手机记事本存为 `lanlink.toml` | 异地、只能靠聊天软件传 |
| 文件 | 点「保存为文件」→ 传文件到手机 → 导入 | 手机能收文件 |

二维码使用 ZXing（Apache-2.0，纯本地生成，不联网、不上传任何内容）。

## 顺带修掉的两个问题

- **成员列表刷新后跳到顶部**：改为内容未变化时不动文本，滚动位置不再被重置
- **邀请码识别过严**：全角冒号、全角井号、全角横线、不间断空格现在能自动归一化

## 说明

强制中继时**两端必须挂在同一个节点上**。社区公共节点彼此独立，
一端挂 A 节点、一端挂 B 节点，两边都会显示「在线」却互相看不见。
本版起，界面会对这种情况给出明确提示。
"""),

    ('v2.6.3', 'v2.6.3 · 修复「勾了强制中继却仍然直连」（当前推荐）', True, """\
## 修复：勾选「强制统一中继」后重启无效、两端仍不互通

**现象**：电脑端勾了强制统一中继、填了节点，手机端导入 TOML，
两边都显示在线但互相看不到成员。

**根因**：勾选框和节点输入框在**建房之后**才被修改。内核进程在启动那一刻
就把命令行参数定死了，之后再改界面不会影响已经跑起来的内核。于是：

- 界面显示「强制统一中继 · tcp://你的节点:11010」
- 内核实际跑的是「直连优先 + 三个社区公共节点」（日志第 2 行可查）
- 手机端按新配置只挂到你的节点上

两端没有任何共同通道，自然互不可见。这**不是**域名失效、也**不是**打洞被拦截。

**修复**：
- 内核启动时记录**实际生效**的参数（是否禁用打洞、是否挂公共节点、节点列表）
- 界面模式与内核实际参数不一致时，手机端配置卡片顶部直接弹出红色告警，
  并写明「需要先停止再重新建房」
- 运行中修改勾选框或节点时，「检查公网 IP」提示变红并说明改动不会生效
- 「复制诊断信息」新增两行：`内核实际:` 与 `内核节点:`，便于远程排查

## 使用建议

改完勾选框或节点后，**先点停止、再重新建房**，然后重新生成手机端配置。

## 附：各版本对比

| 版本 | 二维码/粘贴导入 | 配置跟随模式 | 模式不一致告警 |
|---|---|---|---|
| v2.6.1 | — | ✔ | — |
| v2.6.2 | ✔ | ✔ | — |
| **v2.6.3** | ✔ | ✔ | ✔ |
"""),
]


def main():
    dry = '--dry-run' in sys.argv
    token = io.open(TOKEN_FILE).read().strip()

    rels = gh.api_get('/repos/%s/%s/releases?per_page=100' % (OWNER, REPO), token=token)
    have = {r['tag_name']: r for r in rels}
    log('仓库 %s/%s 现有 Release: %s' % (OWNER, REPO, ', '.join(sorted(have))))
    log('')

    done = []
    for tag, title, recommended, body in PLAN:
        path = pick(tag[1:])
        asset = 'LANLink-%s-win64.zip' % tag
        log('=' * 60)
        log('%s -> %s' % (tag, asset))
        if not path:
            log('  !! 本地找不到包，跳过')
            done.append((tag, asset, 'MISSING', ''))
            continue
        size = os.path.getsize(path)
        log('  磁盘: %s (%.1f MB)' % (os.path.basename(path), size / 1048576))
        if dry:
            log('  [dry-run] 将创建/更新 Release 并上传')
            done.append((tag, asset, 'DRY', ''))
            continue

        # 1) 建或取 Release
        if tag in have:
            rel = have[tag]
            log('  Release 已存在，更新标题与说明')
            rel = gh.api_send('PATCH', '/repos/%s/%s/releases/%d' % (OWNER, REPO, rel['id']),
                              token=token,
                              payload={'name': title, 'body': body,
                                       'prerelease': False, 'draft': False},
                              expect=(200,))
        else:
            try:
                rel = gh.api_send('POST', '/repos/%s/%s/releases' % (OWNER, REPO),
                                  token=token,
                                  payload={'tag_name': tag, 'name': title, 'body': body,
                                           'draft': False, 'prerelease': False,
                                           'target_commitish': 'main'},
                                  expect=(201,))
                log('  已创建 Release')
            except gh.GitHubError as e:
                log('  !! 创建失败: %s' % str(e)[:200])
                done.append((tag, asset, 'FAIL', str(e)[:60]))
                continue

        rel_id = rel['id']
        existing = {a['name'] for a in rel.get('assets', [])}

        # 2) 主包
        if asset in existing:
            # 已存在则删掉重传：同名不同内容会让用户下到旧包
            for a in rel.get('assets', []):
                if a['name'] == asset:
                    gh.api_send('DELETE', '/repos/%s/%s/releases/assets/%d'
                                % (OWNER, REPO, a['id']), token=token, expect=(204,))
                    log('  已删除旧的同名附件')
        log('  上传 %.1f MB …' % (size / 1048576))
        t0 = time.time()
        url = ('https://uploads.github.com/repos/%s/%s/releases/%d/assets?name=%s'
               % (OWNER, REPO, rel_id, asset))
        try:
            gh.upload_asset(url, token, path, upload_name=asset, timeout=3600)
        except Exception as e:
            log('  !! 上传失败: %s' % str(e)[:200])
            done.append((tag, asset, 'FAIL', str(e)[:60]))
            continue
        dt = time.time() - t0
        log('  完成 %.0fs (%.1f MB/s)' % (dt, size / 1048576 / max(dt, .001)))

        # 3) 校验值
        digest = sha256(path)
        sha_name = asset + '.sha256'
        try:
            if sha_name in existing:
                for a in rel.get('assets', []):
                    if a['name'] == sha_name:
                        gh.api_send('DELETE', '/repos/%s/%s/releases/assets/%d'
                                    % (OWNER, REPO, a['id']), token=token, expect=(204,))
            tmp = os.path.join(os.environ.get('TEMP', '.'), sha_name)
            io.open(tmp, 'w', encoding='utf-8', newline='\n').write('%s  %s\n' % (digest, asset))
            url = ('https://uploads.github.com/repos/%s/%s/releases/%d/assets?name=%s'
                   % (OWNER, REPO, rel_id, sha_name))
            gh.upload_asset(url, token, tmp, upload_name=sha_name, timeout=180)
            log('  + %s' % sha_name)
        except Exception as e:
            log('  （校验值上传失败: %s）' % str(e)[:120])

        done.append((tag, asset, 'OK', digest[:16]))
        log('')

    log('=' * 60)
    log('结果汇总')
    for tag, asset, st, extra in done:
        log('  %-8s %-32s %-5s %s' % (tag, asset, st, extra))
    log('')
    log('https://github.com/%s/%s/releases' % (OWNER, REPO))
    return 0 if all(s in ('OK', 'DRY') for _, _, s, _ in done) else 1


if __name__ == '__main__':
    sys.exit(main())
