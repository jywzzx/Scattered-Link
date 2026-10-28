# -*- coding: utf-8 -*-
"""
最终验收：从 GitHub 下载 v2.4.0 附件，解压后实跑 --uitest。
这是唯一能证明「用户下载到的东西真的能用」的验证。
"""
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gh_client as gh

TOKEN = sys.argv[1].strip()
OWNER, REPO = 'jywzzx', 'Sparsa'
TMP = os.path.join(tempfile.gettempdir(), 'll_final_acceptance')

print('=== 1. 查询 v2.4.0 附件 ===')
rel = gh.api_get('/repos/%s/%s/releases/tags/v2.4.0' % (OWNER, REPO), token=TOKEN)
asset = next(a for a in rel['assets'] if a['name'].endswith('.zip'))
print('  %s  %.1f MB' % (asset['name'], asset['size'] / 1048576))

print()
print('=== 2. 从 GitHub 下载（跟随 302 到 CDN） ===')
from urllib.parse import urlparse
host = 'api.github.com'
ip = gh.resolve(host)[0]
c = gh.PinnedHTTPS(host, ip, timeout=60)
c.request('GET', '/repos/%s/%s/releases/assets/%d' % (OWNER, REPO, asset['id']),
          headers={'Authorization': 'Bearer ' + TOKEN,
                   'Accept': 'application/octet-stream',
                   'User-Agent': 'verify'})
r = c.getresponse()
loc = r.getheader('Location')
c.close()

shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(TMP, exist_ok=True)
zip_path = os.path.join(TMP, asset['name'])

if r.status in (301, 302, 303, 307, 308) and loc:
    u = urlparse(loc)
    ips = gh.resolve(u.hostname)
    path = u.path + ('?' + u.query if u.query else '')
    print('  重定向到 CDN: %s' % u.hostname)
    c = gh.PinnedHTTPS(u.hostname, ips[0], timeout=300)
    c.request('GET', path, headers={'User-Agent': 'verify'})
    r = c.getresponse()
    total = 0
    with open(zip_path, 'wb') as f:
        while True:
            chunk = r.read(1024 * 256)
            if not chunk:
                break
            f.write(chunk)
            total += len(chunk)
    c.close()
    print('  下载完成 %.1f MB (HTTP %s)' % (total / 1048576, r.status))
else:
    print('  未重定向，直接下载失败 HTTP %s' % r.status)
    sys.exit(1)

print()
print('=== 3. 解压 ===')
ex = os.path.join(TMP, 'extracted')
os.makedirs(ex, exist_ok=True)
import zipfile
with zipfile.ZipFile(zip_path) as z:
    z.extractall(ex)
    root = sorted(set(n.split('/')[0] for n in z.namelist()))[0]
print('  根目录: %s' % root)
exe = os.path.join(ex, root, 'LANLink.exe')
print('  exe 存在: %s' % os.path.exists(exe))

print()
print('=== 4. 实跑 --uitest（界面自检） ===')
r = subprocess.run([exe, '--uitest'], capture_output=True, text=True,
                   encoding='utf-8', errors='replace', timeout=180)
print('  退出码: %d' % r.returncode)
print('  ' + (r.stdout or '').strip().replace('\n', '\n  '))
if r.returncode != 0:
    print('  STDERR:', (r.stderr or '')[:400])

print()
print('=== 5. 实跑 --check（安装自检） ===')
r2 = subprocess.run([exe, '--check'], capture_output=True, text=True,
                    encoding='utf-8', errors='replace', timeout=180)
print('  退出码: %d' % r2.returncode)
print('  ' + (r2.stdout or '').strip().replace('\n', '\n  '))

print()
print('=== 验收结论 ===')
ok = (r.returncode == 0 and r2.returncode == 0)
print('  从 GitHub 下载的包，界面自检与安装自检均通过: %s' % ('是' if ok else '否'))
shutil.rmtree(TMP, ignore_errors=True)
sys.exit(0 if ok else 1)
