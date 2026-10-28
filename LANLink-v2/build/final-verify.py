# -*- coding: utf-8 -*-
"""v2.4.0 发布包最终校验：解包后实跑 --uitest 与 --check"""
import io
import os
import shutil
import subprocess
import tempfile
import zipfile

ZP = r'D:\局域网联机软件\LANLink-v2\release\LANLink-v2.4.0-win64.zip'
TMP = os.path.join(tempfile.gettempdir(), 'll_final_verify')

z = zipfile.ZipFile(ZP)
names = z.namelist()
root = sorted(set(x.split('/')[0] for x in names))[0]

print('=== 1. 包结构 ===')
print('zip %s MB, %d 文件, 根目录 %s'
      % (round(os.path.getsize(ZP) / 1048576, 1), len(names), root))

must = ['LANLink.exe', 'app/LANLink.jar', 'app/engine/easytier-core.exe',
        'app/engine/wintun.dll', '手机端/LANLink-手机版.apk',
        '1-先看这里-使用说明.txt', '2-给朋友-怎么加入.txt', '3-手机端-使用说明.txt']
missing = 0
for m in must:
    ok = (root + '/' + m) in names
    if not ok:
        missing += 1
    print('  %s %s' % ('OK  ' if ok else 'MISS', m))

print()
print('=== 2. jar 内含新架构 MainWindow ===')
jf = zipfile.ZipFile(io.BytesIO(z.read(root + '/app/LANLink.jar')))
classes = [c for c in jf.namelist() if c.endswith('.class')]
print('  MainWindow 存在:', any('MainWindow' in c for c in classes))

print()
print('=== 3. 解包并实跑 ===')
shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(TMP, exist_ok=True)
z.extractall(TMP)
exe = os.path.join(TMP, root, 'LANLink.exe')

for mode, label in (('--uitest', '界面自检'), ('--check', '安装自检')):
    r = subprocess.run([exe, mode], capture_output=True, text=True,
                       encoding='utf-8', errors='replace', timeout=150)
    print('--- %s (%s) rc=%d ---' % (label, mode, r.returncode))
    print((r.stdout or '').strip()[:600])
    if r.stderr.strip():
        print('  STDERR:', r.stderr.strip()[:300])
    if r.returncode != 0:
        missing += 1

print()
print('=== 4. 文档完整性 ===')
for d in ('1-先看这里-使用说明.txt', '2-给朋友-怎么加入.txt', '3-手机端-使用说明.txt'):
    t = z.read(root + '/' + d).decode('utf-8')
    fences = t.count('```')
    blank = t.count('\n\n\n')
    print('  %-24s %4d 字, ``` x%d, 连续空行 %d' % (d, len(t), fences, blank))
    if fences % 2 or blank:
        missing += 1

print()
print('RESULT:', 'ALL OK' if missing == 0 else ('%d 项异常' % missing))
shutil.rmtree(TMP, ignore_errors=True)
