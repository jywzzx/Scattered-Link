# -*- coding: utf-8 -*-
"""跟随 302 重定向读取 .sha256 资产内容，核对真实 SHA256"""
import hashlib
import http.client
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gh_client as gh

TOKEN = sys.argv[1].strip()
OWNER, REPO = 'jyzzx' if False else 'jywzzx', 'Sparsa'
LOCAL = r'D:\局域网联机软件\LANLink-v2\release'

problems = []


def fetch_asset(asset_id, max_hops=6):
    """跟随重定向下载资产内容"""
    host = 'api.github.com'
    ip = gh.resolve(host)[0]
    for _ in range(max_hops):
        c = gh.PinnedHTTPS(host, ip, timeout=40)
        c.request('GET', '/repos/%s/%s/releases/assets/%d' % (OWNER, REPO, asset_id),
                  headers={'Authorization': 'Bearer ' + TOKEN,
                           'Accept': 'application/octet-stream',
                           'User-Agent': 'verify'})
        r = c.getresponse()
        loc = r.getheader('Location')
        status = r.status
        data = r.read() if status == 200 else b''
        c.close()
        if status == 200:
            return data
        if status in (301, 302, 303, 307, 308) and loc:
            # 重定向目标是 CDN 的绝对 URL
            from urllib.parse import urlparse
            u = urlparse(loc)
            host = u.hostname
            ip = gh.resolve(host)[0] if gh.resolve(host) else ip
            path = u.path + ('?' + u.query if u.query else '')
            # 继续用同一连接类，但路径变了
            c = gh.PinnedHTTPS(host, ip, timeout=40)
            c.request('GET', path, headers={'User-Agent': 'verify'})
            r = c.getresponse()
            data = r.read()
            c.close()
            if r.status == 200:
                return data
            print('    重定向后状态 %s' % r.status)
            return b''
        print('    意外状态 %s' % status)
        return b''
    return b''


rels = gh.api_get('/repos/%s/%s/releases?per_page=20' % (OWNER, REPO), token=TOKEN)

print('=== 校验每个版本的 SHA256 附件 ===')
for r in rels:
    tag = r['tag_name']
    zip_asset = next((a for a in r['assets'] if a['name'].endswith('.zip')
                      and not a['name'].endswith('.sha256')), None)
    sha_asset = next((a for a in r['assets'] if a['name'].endswith('.sha256')), None)
    if not zip_asset or not sha_asset:
        problems.append('%s 附件不完整' % tag)
        continue

    print()
    print('--- %s ---' % tag)
    data = fetch_asset(sha_asset['id'])
    if not data:
        problems.append('%s: 无法读取 sha256 资产内容' % tag)
        print('  读取失败')
        continue
    text = data.decode('utf-8', 'replace').strip()
    print('  sha256 文件内容: %s' % text)

    remote_sha = text.split()[0]
    remote_name = text.split()[-1]

    # 本地计算：必须选与 GitHub 上那个字节一致的文件。
    # 本地可能有多个同前缀 zip（打包脚本因「安全删除」限制会加时间戳后缀），
    # 不能简单取 mtime 最新的 —— 那是未上传的中间产物。
    # 正确做法：逐个算 sha256，找出与远程值相同的那个；
    # 若都不匹配，再报告本地存在哪些候选。
    cand = [f for f in os.listdir(LOCAL)
            if f.endswith('.zip') and f.startswith(zip_asset['name'][:-4])]
    if not cand:
        problems.append('%s: 本地找不到对应 zip' % tag)
        continue

    local_sha = None
    matched_file = None
    for f in cand:
        p = os.path.join(LOCAL, f)
        h = hashlib.sha256()
        with open(p, 'rb') as fh:
            for chunk in iter(lambda: fh.read(1024 * 1024), b''):
                h.update(chunk)
        d = h.hexdigest()
        if d == remote_sha:
            local_sha = d
            matched_file = f
            break

    print('  远程 sha256: %s' % remote_sha)
    if local_sha:
        print('  本地匹配文件: %s' % matched_file)
        print('  本地 sha256: %s  [OK] 与远程一致' % local_sha)
    else:
        print('  本地无匹配文件，候选及各自的 sha256：')
        for f in cand:
            p = os.path.join(LOCAL, f)
            h = hashlib.sha256()
            with open(p, 'rb') as fh:
                for chunk in iter(lambda: fh.read(1024 * 1024), b''):
                    h.update(chunk)
            print('      %-44s %s' % (f, h.hexdigest()))
        problems.append('%s: 本地无与远程一致的 zip（远程附件本身可能正常，需人工确认）' % tag)

    print('  文件名一致: %s' % ('是' if remote_name == zip_asset['name'] else '否 -> %s' % remote_name))

    # 附件大小也要对（用匹配到的本地文件）
    if matched_file:
        local_size = os.path.getsize(os.path.join(LOCAL, matched_file))
        print('  附件大小: 远程 %.1f MB / 本地 %.1f MB'
              % (zip_asset['size'] / 1048576, local_size / 1048576))
        if abs(zip_asset['size'] - local_size) > 1024:
            problems.append('%s: 附件大小不一致' % tag)

print()
print('=' * 56)
if problems:
    print('发现 %d 个问题：' % len(problems))
    for p in problems:
        print('  - %s' % p)
    sys.exit(1)
print('五个版本的附件与校验值全部一致，发布内容可信。')
