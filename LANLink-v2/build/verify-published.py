# -*- coding: utf-8 -*-
"""
从 GitHub 侧独立验证发布结果。
不看本地日志，直接查 API 确认 Release、附件、大小、SHA256 是否真的对得上。
"""
import hashlib
import io
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gh_client as gh

TOKEN = sys.argv[1].strip()
OWNER = 'jywzzx'
REPO = 'Sparsa'
LOCAL = r'D:\局域网联机软件\LANLink-v2\release'

problems = []

print('=== 1. 仓库与 Release 列表 ===')
rels = gh.api_get('/repos/%s/%s/releases?per_page=20' % (OWNER, REPO), token=TOKEN)
print('  Release 数量: %d' % len(rels))
for r in rels:
    print('  %-10s %-38s assets=%d  prerelease=%s  draft=%s'
          % (r.get('tag_name'), r.get('name', '')[:38],
             len(r.get('assets', [])), r.get('prerelease'), r.get('draft')))

print()
print('=== 2. 逐个附件校验（大小 + SHA256） ===')
EXPECT = {
    'v2.0.0': 'LANLink-v2-win64.zip',
    'v2.1.0': 'LANLink-v2.1.0-win64.zip',
    'v2.2.0': 'LANLink-v2.2.0-win64.zip',
    'v2.3.0': 'LANLink-v2.3.0-win64.zip',
    'v2.4.0': 'LANLink-v2.4.0-win64.zip',
}

for tag, fname in EXPECT.items():
    rel = next((x for x in rels if x['tag_name'] == tag), None)
    if not rel:
        problems.append('%s: Release 不存在' % tag)
        continue
    assets = {a['name']: a for a in rel.get('assets', [])}
    a = assets.get(fname)
    if not a:
        problems.append('%s: 附件 %s 缺失（现有 %s）' % (tag, fname, list(assets)))
        continue

    # 本地同名 zip 的 sha256（磁盘名可能带时间戳后缀）
    cand = []
    for f in os.listdir(LOCAL):
        if f.endswith('.zip') and f.startswith(fname[:-4]):
            cand.append(os.path.join(LOCAL, f))
    local_sha = None
    if cand:
        p = max(cand, key=os.path.getmtime)
        h = hashlib.sha256()
        with open(p, 'rb') as fh:
            for chunk in iter(lambda: fh.read(1024 * 1024), b''):
                h.update(chunk)
        local_sha = h.hexdigest()

    # GitHub 上传的 sha256 文件内容
    sha_file = assets.get(fname + '.sha256')
    remote_sha = None
    if sha_file:
        try:
            import base64
            import http.client
            import ssl
            ip = gh.resolve('api.github.com')[0]
            c = gh.PinnedHTTPS('api.github.com', ip, timeout=40)
            c.request('GET', '/repos/%s/%s/releases/assets/%d'
                      % (OWNER, REPO, sha_file['id']),
                      headers={'Authorization': 'Bearer ' + TOKEN,
                               'Accept': 'application/octet-stream',
                               'User-Agent': 'verify'})
            r = c.getresponse()
            data = r.read()
            c.close()
            if r.status == 200:
                remote_sha = data.decode('utf-8', 'replace').split()[0]
        except Exception as e:
            print('    (读取 sha256 资产失败: %s)' % str(e)[:50])

    size_mb = a['size'] / 1048576
    match = 'OK ' if (local_sha and remote_sha and local_sha == remote_sha) else '?? '
    if local_sha and remote_sha and local_sha != remote_sha:
        problems.append('%s: SHA256 不一致' % tag)

    print('  %s %-9s %-30s %6.1f MB  %s'
          % (match, tag, fname, size_mb,
             (remote_sha or '无')[:16]))

    # 大小合理性
    if size_mb < 60:
        problems.append('%s: 附件偏小（%.1f MB）' % (tag, size_mb))

print()
print('=== 3. 仓库文件 ===')
for path in ('README.md', 'LICENSE'):
    try:
        f = gh.api_get('/repos/%s/%s/contents/%s' % (OWNER, REPO, path), token=TOKEN)
        print('  %-12s %d 字节' % (path, f.get('size', 0)))
    except Exception as e:
        problems.append('%s 缺失: %s' % (path, str(e)[:60]))
        print('  %-12s 缺失' % path)

print()
print('=== 4. 最新版本标记 ===')
try:
    latest = gh.api_get('/repos/%s/%s/releases/latest' % (OWNER, REPO), token=TOKEN)
    print('  Latest = %s (%s)' % (latest.get('tag_name'), latest.get('name')))
    if latest.get('tag_name') != 'v2.4.0':
        print('  (提示：Latest 不是 v2.4.0，可在页面右上角手动设为 Latest)')
except Exception as e:
    print('  (无法获取 latest: %s)' % str(e)[:80])

print()
print('=== 5. Release 说明文字抽查 ===')
r24 = next((x for x in rels if x['tag_name'] == 'v2.4.0'), None)
if r24:
    body = r24.get('body') or ''
    print('  v2.4.0 说明长度: %d 字符' % len(body))
    print('  首行: %s' % (body.split('\n')[0][:70] if body else '(空)'))
    if len(body) < 50:
        problems.append('v2.4.0 Release 说明过短')

print()
print('=' * 50)
if problems:
    print('发现 %d 个问题：' % len(problems))
    for p in problems:
        print('  - %s' % p)
else:
    print('全部验证通过')
print('仓库: https://github.com/%s/%s' % (OWNER, REPO))
print('发布: https://github.com/%s/%s/releases' % (OWNER, REPO))
