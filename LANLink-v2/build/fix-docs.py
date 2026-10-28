# -*- coding: utf-8 -*-
"""
清理文档中的多余空行（代码块内的空行保留），
并报告是否有段落被 shell 反引号替换剥空。

用法: python fix-docs.py [--check]
"""
import io
import os
import sys

ROOT = r'D:\局域网联机软件\LANLink-v2'
DOCS = [
    'docs\\REVIEW.md',
    '1-先看这里-使用说明.txt',
    '2-给朋友-怎么加入.txt',
    '3-手机端-使用说明.txt',
]

CHECK_ONLY = '--check' in sys.argv


def collapse(lines):
    """折叠连续空行为单个；保留首尾各一个空行结构"""
    out = []
    prev_blank = True  # 开头的空行也算多余
    for l in lines:
        blank = (l.strip() == '')
        if blank and prev_blank:
            continue
        out.append(l)
        prev_blank = blank
    # 去掉文末空行
    while out and out[-1].strip() == '':
        out.pop()
    return out


for rel in DOCS:
    p = os.path.join(ROOT, rel)
    if not os.path.exists(p):
        print('SKIP (missing)  %s' % rel)
        continue

    src = io.open(p, encoding='utf-8').read()
    lines = src.split('\n')

    # 按代码块分段处理：块内保持原样，块外折叠空行
    segs = []
    cur = []
    in_code = False
    for l in lines:
        if l.strip().startswith('```'):
            if in_code:
                cur.append(l)
                segs.append(('code', cur))
                cur = []
                in_code = False
            else:
                segs.append(('text', cur))
                cur = [l]
                in_code = True
            continue
        cur.append(l)
    if in_code:
        segs.append(('code', cur))
    elif cur:
        segs.append(('text', cur))

    out = []
    for kind, seg in segs:
        if kind == 'code':
            out.extend(seg)
        else:
            out.extend(collapse(seg))

    while out and out[-1].strip() == '':
        out.pop()

    fences = sum(1 for l in out if l.strip().startswith('```'))
    status = 'OK' if fences % 2 == 0 else '!! 代码块未闭合'

    if not CHECK_ONLY:
        io.open(p, 'w', encoding='utf-8', newline='').write('\n'.join(out) + '\n')

    print('%-26s %4d -> %4d 行  ``` x%-3d %s'
          % (rel, len(lines), len(out), fences, status))
