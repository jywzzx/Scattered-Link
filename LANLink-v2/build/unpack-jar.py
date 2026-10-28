"""把 zxing-core.jar 解到指定目录，用于与业务类一起打进同一个 jar。

为什么不用 `jar --extract`：Windows PowerShell 对 `--file a.jar -C dir` 这种
多段参数偶发解析失败（"解析文件参数时出错"），而且失败信息对人不友好。
用 zipfile 解开是最直白、最不容易踩路径引号坑的做法。
"""
import os
import sys
import zipfile


def main():
    if len(sys.argv) < 3:
        print('用法: unpack-jar.py <src.jar> <dstDir>')
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    if not os.path.exists(src):
        print('!! 源 jar 不存在: %s' % src)
        return 1
    os.makedirs(dst, exist_ok=True)
    with zipfile.ZipFile(src) as z:
        n = 0
        for info in z.infolist():
            if info.is_dir():
                continue
            # 丢掉签名与清单：与业务类合并时 META-INF 会冲突
            name = info.filename
            if name.startswith('META-INF/'):
                continue
            target = os.path.join(dst, name.replace('/', os.sep))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with z.open(info) as f, open(target, 'wb') as o:
                o.write(f.read())
            n += 1
    print('[unpack] %d 个文件 -> %s' % (n, dst))
    return 0


if __name__ == '__main__':
    sys.exit(main())
