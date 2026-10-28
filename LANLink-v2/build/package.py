"""把 jpackage 产物 + 文档 + 手机端 APK 组装成最终 zip，并做安装自检。"""
import os
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 允许用环境变量或命令行参数指定 jpackage 产物目录
# （程序正在运行时 build/LANLink 会被占用的内核文件锁住，只能旁路构建）
APP = (sys.argv[1] if len(sys.argv) > 1 else None) or os.environ.get('LANLINK_APP_DIR') \
    or os.path.join(ROOT, 'build', 'stage2', 'LANLink')
DIST = os.path.join(ROOT, 'dist')
REL = os.path.join(ROOT, 'release')
# 带版本号的文件名：旧包可能被资源管理器或预览工具占用，版本化可避免覆盖失败
VERSION = '2.6.3'
STAGE = os.path.join(REL, 'LANLink-v%s-win64' % VERSION)
ZIP = os.path.join(REL, 'LANLink-v%s-win64.zip' % VERSION)


def log(m):
    print(m, flush=True)


def _clear_readonly(func, path, excinfo):
    """复制失败且原因是只读时，先去掉只读属性再重试一次。

    jpackage 产出的 exe 带有只读属性，copy2 复制元数据时会失败。
    注意：这不是文件被占用 —— 占用会连重命名都做不了。
    """
    try:
        os.chmod(path, 0o666)
        return func(path)
    except Exception:
        raise excinfo[1]


def _copy_tree(src, dst):
    """递归复制目录树。

    自己实现而非用 shutil.copytree，原因有二：
    1. 本机 Python 的 copytree 不支持 onerror / onexc 回调参数；
    2. copy2 会连同只读属性一起复制，jpackage 产出的 exe 带只读属性，
       覆盖既有文件时会抛 Permission denied。
       （注意这不是文件被占用 —— 被占用时连重命名都会失败。）
    """
    errors = []
    src = os.path.abspath(src)
    dst = os.path.abspath(dst)
    os.makedirs(dst, exist_ok=True)

    for base, dirs, files in os.walk(src):
        rel = os.path.relpath(base, src)
        target_dir = dst if rel == '.' else os.path.join(dst, rel)
        os.makedirs(target_dir, exist_ok=True)
        for d in dirs:
            os.makedirs(os.path.join(target_dir, d), exist_ok=True)
        for f in files:
            s = os.path.join(base, f)
            d = os.path.join(target_dir, f)
            try:
                if os.path.exists(d):
                    try:
                        os.chmod(d, 0o666)
                    except Exception:
                        pass
                shutil.copyfile(s, d)
            except Exception as e:
                errors.append((f, str(e)[:100]))

    if errors:
        log('  ! %d 个文件复制失败:' % len(errors))
        for n, e in errors[:6]:
            log('      %s  (%s)' % (n, e[:60]))
    return errors


def _safe_replace(src, dst):
    """把 src 复制为 dst。目标已存在且不可覆盖时，退回到带时间戳的新文件名。

    本机沙箱启用了「安全删除」：os.remove 会尝试移入回收站，回收站失败时抛错，
    文件随后被标记为不可删除，导致后续覆盖写全部 Permission denied。
    因此这里不删除任何东西，只做「写新文件」。
    """
    if not os.path.exists(dst):
        shutil.copyfile(src, dst)
        return dst
    try:
        shutil.copyfile(src, dst)
        return dst
    except Exception:
        base, ext = os.path.splitext(dst)
        alt = '%s-%s%s' % (base, time.strftime('%H%M%S'), ext)
        shutil.copyfile(src, alt)
        log('  ! 目标不可覆盖，改用新文件名: %s' % os.path.basename(alt))
        return alt


def main():
    if not os.path.isdir(APP):
        log('!! 找不到 build/LANLink，先跑 build.ps1')
        return 1

    exe = os.path.join(APP, 'LANLink.exe')
    if not os.path.exists(exe):
        log('!! 缺少 LANLink.exe')
        return 1

    # 安装自检：直接跑打包后的 exe（--check 不弹窗、不建网卡）
    r = subprocess.run([exe, '--check'], capture_output=True, timeout=120)
    out = (r.stdout or b'').decode('utf-8', 'replace').strip()
    log('--- 安装自检 (exit=%d) ---' % r.returncode)
    log(out)
    ok = r.returncode == 0 and 'easytier-core.exe' in out

    log('组装 dist（可能被运行中的程序占用，失败不影响发布）')
    try:
        os.makedirs(DIST, exist_ok=True)
        _copy_tree(APP, os.path.join(DIST, 'LANLink'))
    except Exception as e:
        log('  跳过 dist 副本: %s' % str(e)[:80])

    log('组装 release')
    # 关键：每次打包写入一个全新目录，绝不覆盖或删除已存在的目录。
    # 本机沙箱启用了「安全删除」——删除文件时移入回收站，回收站操作失败会导致
    # os.remove 抛错，文件随后被标记为不可删除，进而覆盖写全部 Permission denied。
    # 表现为「19 个文件复制失败」且重试无效。改为只写新文件即可彻底规避。
    stage_dir = os.path.join(REL, os.path.basename(STAGE))
    if os.path.isdir(stage_dir):
        stamp = time.strftime('%m%d-%H%M%S')
        stage_dir = os.path.join(REL, '%s-%s' % (os.path.basename(STAGE), stamp))
        log('  目标目录已存在，改用新目录：%s' % os.path.basename(stage_dir))
    zip_path = os.path.join(REL, '%s.zip' % os.path.basename(stage_dir))
    os.makedirs(stage_dir, exist_ok=True)
    copy_errors = _copy_tree(APP, stage_dir)
    if copy_errors:
        log('  !! 有 %d 个文件复制失败：' % len(copy_errors))
        for n, e in copy_errors[:6]:
            log('      %s' % n)
        log('  !! 继续打包，但完整性校验会拦截不完整的包')
    else:
        log('  复制完成')

    for name in ('1-先看这里-使用说明.txt', '2-给朋友-怎么加入.txt',
                 '3-手机端-使用说明.txt', 'LICENSE'):
        src = os.path.join(ROOT, name)
        if os.path.exists(src):
            try:
                os.chmod(src, 0o666)
            except Exception:
                pass
            shutil.copy(src, stage_dir)
            log('  + ' + name)

    apk = os.path.join(ROOT, 'phone', 'LANLink-手机版.apk')
    if os.path.exists(apk):
        d = os.path.join(stage_dir, '手机端')
        os.makedirs(d, exist_ok=True)
        shutil.copy(apk, d)
        log('  + 手机端/LANLink-手机版.apk (%.1f MB)' % (os.path.getsize(apk) / 1048576))
    else:
        log('  !! 缺少 phone/LANLink-手机版.apk')

    log('打包 zip')
    # 直接以 'w' 模式覆盖写，不预先删除（预删会触发回收站机制而被拦截）
    # zip 内的根目录名统一用规范名（不含时间戳），用户解压后目录名干净
    zip_root = os.path.basename(STAGE).split('-win64')[0] + '-win64'
    raw = 0
    count = 0
    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for base, _, files in os.walk(stage_dir):
            for f in files:
                fp = os.path.join(base, f)
                arc = os.path.join(zip_root, os.path.relpath(fp, stage_dir))
                z.write(fp, arc)
                raw += os.path.getsize(fp)
                count += 1

    log('文件数 %d，原始 %.1f MB，压缩后 %.1f MB' % (count, raw / 1048576, os.path.getsize(zip_path) / 1048576))
    log('zip 内根目录: %s' % zip_root)

    # 出包守门：关键文件缺失或明显偏小（复制被中断）时拒绝发布
    log('校验发布包完整性')
    problems = []
    with zipfile.ZipFile(zip_path) as z:
        names = z.namelist()
        root = zip_root
        for m in ('LANLink.exe', 'app/LANLink.jar', 'app/engine/easytier-core.exe',
                  'app/engine/wintun.dll', '手机端/LANLink-手机版.apk',
                  '1-先看这里-使用说明.txt', '2-给朋友-怎么加入.txt',
                  '3-手机端-使用说明.txt', 'LICENSE'):
            p = '%s/%s' % (root, m)
            if p not in names:
                problems.append('缺失 ' + m)
            else:
                sz = z.getinfo(p).file_size
                if sz < 1024:
                    problems.append('%s 大小异常（%d 字节）' % (m, sz))
        # JRE 运行时目录必须存在且文件数合理
        rt = [n for n in names if '/runtime/bin/' in n]
        if len(rt) < 5:
            problems.append('runtime/bin 文件过少（%d 个），JRE 可能未完整复制' % len(rt))
        core = [n for n in names if n.endswith('easytier-core.exe')]
        if core and z.getinfo(core[0]).file_size < 5 * 1024 * 1024:
            problems.append('easytier-core.exe 大小异常，可能未完整复制')

    if problems:
        log('!! 校验未通过，共 %d 项问题：' % len(problems))
        for p in problems:
            log('     %s' % p)
        log('!! 拒绝发布。请关闭正在运行的程序后重新打包。')
        return 4

    log('校验通过：关键文件齐全')
    log('输出: ' + zip_path)
    # 供发布脚本读取最终文件名
    with open(os.path.join(ROOT, 'build', 'last-package.txt'), 'w', encoding='utf-8') as f:
        f.write(zip_path)
    return 0 if ok else 3


if __name__ == '__main__':
    sys.exit(main())
