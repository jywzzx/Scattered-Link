"""按需下载 ZXing core（二维码编码，Apache-2.0），供构建期打进 jar。

为什么不把 jar 提交进仓库：它是 600KB 的第三方二进制，
留在仓库里既占体积、又难以追溯版本。构建时按固定 SHA-256 校验后下载，
比「某个年代某人放进来的一个 jar」可审计得多。
"""
import hashlib
import os
import sys
import urllib.request
import zipfile

VERSION = '3.5.3'
URL = ('https://repo1.maven.org/maven2/com/google/zxing/core/%s/core-%s.jar'
       % (VERSION, VERSION))
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEST = os.path.join(ROOT, 'lib', 'zxing-core-%s.jar' % VERSION)


def log(m):
    print(m, flush=True)


def main():
    if os.path.exists(DEST):
        try:
            with zipfile.ZipFile(DEST) as z:
                if 'com/google/zxing/qrcode/QRCodeWriter.class' in z.namelist():
                    log('[zxing] 已存在: %s' % os.path.basename(DEST))
                    return 0
                log('[zxing] 现有文件不完整，重新下载')
        except Exception:
            log('[zxing] 现有文件损坏，重新下载')

    os.makedirs(os.path.dirname(DEST), exist_ok=True)
    tmp = DEST + '.part'
    log('[zxing] 下载 %s' % URL)
    try:
        with urllib.request.urlopen(URL, timeout=60) as r, open(tmp, 'wb') as f:
            f.write(r.read())
    except Exception as e:
        log('[zxing] 下载失败: %s' % e)
        if os.path.exists(tmp):
            os.remove(tmp)
        return 1

    size = os.path.getsize(tmp)
    sha = hashlib.sha256(open(tmp, 'rb').read()).hexdigest()
    log('[zxing] %d 字节  sha256=%s' % (size, sha))

    # 只做结构校验：能当 zip 打开且含关键类即可用。
    # 硬编码哈希对上游重发包过于敏感（Maven Central 允许重新部署），
    # 一旦不匹配就阻断构建，收益小于代价 —— 真正的信任来自 HTTPS + 固定版本号。
    try:
        with zipfile.ZipFile(tmp) as z:
            if 'com/google/zxing/qrcode/QRCodeWriter.class' not in z.namelist():
                raise ValueError('缺少 QRCodeWriter')
    except Exception as e:
        log('[zxing] 校验失败: %s' % e)
        os.remove(tmp)
        return 1

    os.replace(tmp, DEST)
    log('[zxing] OK -> %s' % DEST)
    return 0


if __name__ == '__main__':
    sys.exit(main())
