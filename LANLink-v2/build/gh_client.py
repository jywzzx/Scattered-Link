# -*- coding: utf-8 -*-
"""
GitHub 直连客户端：绕过本机 hosts 劫持与系统代理。

背景：
- hosts 文件把 api.github.com / github.com 等全部指向 127.0.0.1
- 系统存在 Steam++ 加速器的代理管道，curl 默认走代理导致 --resolve 失效
- 实测：Python 原生 socket + SNI 直连真实 IP 可正常访问 GitHub API

本模块通过「真实 IP + 保留 SNI 与 Host 头」的方式直连，
既不需要改 hosts，也不需要清代理设置。
"""
import http.client
import json
import os
import re
import socket
import ssl
import subprocess
import time

NSLOOKUP = r'C:\WINDOWS\System32\nslookup.exe'
PUBLIC_DNS = ['223.5.5.5', '119.29.29.29', '8.8.8.8', '114.114.114.114']

# 已知可用的 GitHub 真实 IP（nslookup 结果 + 实测验证）
SEED_IPS = {
    'api.github.com': ['20.205.243.168', '20.27.177.116', '140.82.114.6', '20.26.156.210'],
    'github.com': ['20.205.243.166', '172.182.252.133', '140.82.114.4', '20.27.177.113'],
    'uploads.github.com': ['20.205.243.161', '20.26.156.215', '20.27.177.117', '20.201.28.148'],
    'objects.githubusercontent.com': ['185.199.108.133', '185.199.111.133', '185.199.109.133'],
    'codeload.github.com': ['20.205.243.165'],
    'release-assets.githubusercontent.com': ['185.199.108.133', '185.199.109.133',
                                           '185.199.110.133', '185.199.111.133'],
}

_ip_cache = {}


def _nslookup(host):
    """用公共 DNS 查真实 IP，跳过被劫持的系统解析"""
    found = []
    for dns in PUBLIC_DNS:
        try:
            r = subprocess.run([NSLOOKUP, host, dns], capture_output=True,
                               text=True, encoding='utf-8', errors='replace', timeout=15)
            out = r.stdout or ''
            if "can't find" in out.lower() or 'NXDOMAIN' in out:
                continue
            addrs = []
            for line in out.splitlines():
                s = line.strip()
                if s.startswith('Address') or s.startswith('地址'):
                    m = re.search(r'(\d+\.\d+\.\d+\.\d+)', s)
                    if m:
                        addrs.append(m.group(1))
            if addrs:
                found.append(addrs[-1])
        except Exception:
            pass
    return found


def resolve(host):
    """拿到某域名当前可用的真实 IP 列表（带缓存）"""
    if host in _ip_cache:
        return _ip_cache[host]
    cands = list(dict.fromkeys(SEED_IPS.get(host, []) + _nslookup(host)))
    _ip_cache[host] = cands
    return cands


class PinnedHTTPS(http.client.HTTPSConnection):
    """把域名固定到指定 IP，同时保留 SNI 与 Host 头（TLS 证书校验正常）"""

    def __init__(self, host, ip, port=443, timeout=60):
        super().__init__(host, port=port, timeout=timeout,
                         context=ssl.create_default_context())
        self._pinned_ip = ip

    def connect(self):
        sock = socket.create_connection((self._pinned_ip, self.port), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


class GitHubError(Exception):
    def __init__(self, status, body, message=''):
        self.status = status
        self.body = body
        self.message = message or _extract_message(body)
        super().__init__('GitHub API %s: %s' % (status, self.message))


def _extract_message(body):
    try:
        j = json.loads(body.decode('utf-8', 'replace'))
        return j.get('message') or str(j)[:300]
    except Exception:
        return body.decode('utf-8', 'replace')[:300]


def _request(method, host, path, token=None, body=None, headers=None,
             content_type=None, expect=None, timeout=60):
    """发起一次直连请求，自动在多个候选 IP 间重试"""
    hdrs = {
        'User-Agent': 'LANLink-Publisher/2.4.0',
        'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
    }
    if token:
        hdrs['Authorization'] = 'Bearer ' + token
    if content_type:
        hdrs['Content-Type'] = content_type
    if headers:
        hdrs.update(headers)

    last = None
    for ip in resolve(host):
        try:
            c = PinnedHTTPS(host, ip, timeout=timeout)
            c.request(method, path, body=body, headers=hdrs)
            r = c.getresponse()
            data = r.read()
            status = r.status
            c.close()
            if expect and status not in expect:
                last = GitHubError(status, data)
                continue
            return status, data
        except GitHubError as e:
            last = e
        except Exception as e:
            last = e
            continue
    raise last if last else RuntimeError('无可用 IP：%s' % host)


def api_get(path, token=None, host='api.github.com'):
    st, data = _request('GET', host, path, token=token, expect=(200,))
    return json.loads(data.decode('utf-8'))


def api_send(method, path, token=None, payload=None, expect=(200, 201)):
    body = None
    hdrs = {}
    if payload is not None:
        body = json.dumps(payload).encode('utf-8')
        hdrs['Content-Type'] = 'application/json'
    st, data = _request(method, 'api.github.com', path, token=token,
                        body=body, headers=hdrs, expect=expect)
    if not data:
        return {}
    return json.loads(data.decode('utf-8'))


def upload_asset(url, token, path, upload_name=None, timeout=3600):
    """
    上传 Release 附件。

    url 形如 https://uploads.github.com/repos/<owner>/<repo>/releases/<id>/assets?name=X
    upload_name 可覆盖附件在 GitHub 上的显示名（与磁盘文件名解耦）。

    流程：**直接 PUT** release 对象里的 upload_url（剥掉 {?name,label} 模板）。
    注意 `POST /releases/{id}/assets` 端点在 GitHub 上返回 404，不可用。

    两个关键点：
    1. upload_url 在资产被删除后会重新分配，所以每次重试都重新 GET release 取新 URL；
    2. 上传中途失败会在服务端留下半截资产，导致下次同名上传报 400 Bad request，
       因此失败时要先清理同名资产再重试。
    """
    size = os.path.getsize(path)
    name = upload_name or os.path.basename(path)
    host = 'uploads.github.com'

    hdrs = {
        'Authorization': 'Bearer ' + token,
        'Content-Type': 'application/octet-stream',
        'Content-Length': str(size),
        'User-Agent': 'LANLink-Publisher/2.5.0',
        'Accept': 'application/vnd.github+json',
    }

    import re as _re
    m = _re.search(r'/repos/([^/]+)/([^/]+)/releases/(\d+)/assets', url)
    if not m:
        raise ValueError('无法从 URL 解析 release 信息: %s' % url)
    owner, repo, rid = m.group(1), m.group(2), m.group(3)

    last = None
    for attempt in range(6):
        # 每次尝试都重新取 release，确保拿到当前有效的 upload_url
        try:
            rel = api_get('/repos/%s/%s/releases/%s' % (owner, repo, rid), token=token)
            upload_url = rel.get('upload_url') or url
        except Exception:
            upload_url = url

        if '{' in upload_url:
            upload_url = upload_url[:upload_url.index('{')]
        base = upload_url.split('?', 1)[0]
        path_only = base.split(host, 1)[1] if host in base else base
        path_only += '?name=%s' % name

        # 遍历候选 IP：某个 IP 不通时换下一个，而不是整体重传
        for ip in resolve(host):
            try:
                c = PinnedHTTPS(host, ip, timeout=timeout)
                # socket 级超时：连接建立后若长时间无进展能被感知，
                # 否则坏连接会一直挂着（实测上传卡住 15 分钟无响应）。
                c.sock = None
                c.connect()
                c.sock.settimeout(60)
                c.putrequest('PUT', path_only, skip_accept_encoding=True)
                for k, v in hdrs.items():
                    c.putheader(k, v)
                c.endheaders()
                sent = 0
                t0 = time.time()
                last_report = 0
                with open(path, 'rb') as f:
                    while True:
                        chunk = f.read(1024 * 512)
                        if not chunk:
                            break
                        c.send(chunk)
                        sent += len(chunk)
                        now = time.time()
                        if now - last_report >= 15:
                            last_report = now
                            mb = sent / 1048576
                            spd = mb / max(now - t0, 0.001)
                            print('    上传中 %5.1f/%.1f MB  %.2f MB/s  (%.0fs)'
                                  % (mb, size / 1048576, spd, now - t0), flush=True)
                r = c.getresponse()
                data = r.read()
                status = r.status
                c.close()
                if status in (201, 200):
                    print('    上传完成 %.1f MB，用时 %.0fs'
                          % (size / 1048576, time.time() - t0))
                    return json.loads(data.decode('utf-8')) if data else {}
                last = GitHubError(status, data)
                print('    IP %s 返回 %s，清理残留后重试' % (ip, status), flush=True)
                _delete_asset_by_name(token, owner, repo, rid, name)
                break
            except Exception as e:
                last = e
                print('    IP %s 失败: %s' % (ip, str(e)[:70]), flush=True)
                _delete_asset_by_name(token, owner, repo, rid, name)
                continue
    raise last if last else RuntimeError('上传失败')


def _delete_asset_by_name(token, owner, repo, release_id, name):
    """删除指定名字的资产（清理半截上传的残留）"""
    try:
        rel = api_get('/repos/%s/%s/releases/%s' % (owner, repo, release_id), token=token)
        for a in rel.get('assets', []):
            if a['name'] == name:
                _request('DELETE', 'api.github.com',
                         '/repos/%s/%s/releases/assets/%d' % (owner, repo, a['id']),
                         token=token, expect=(204,))
                print('    已清理同名残留资产 %s' % name, flush=True)
    except Exception:
        pass


def whoami(token):
    """查询 token 对应的账号信息"""
    return api_get('/user', token=token)


def check(token):
    """连通性与权限自检"""
    out = []
    try:
        rate = api_get('/rate_limit')
        out.append(('API 连通', 'OK', 'rate %s/%s' % (rate.get('rate', {}).get('remaining'),
                                                    rate.get('rate', {}).get('limit'))))
    except Exception as e:
        out.append(('API 连通', 'FAIL', str(e)[:120]))
        return out

    try:
        u = whoami(token)
        out.append(('Token 有效', 'OK', '%s (%s)' % (u.get('login'), u.get('name') or '未设昵称')))
        out.append(('账号类型', 'OK', u.get('type', '?')))
    except Exception as e:
        out.append(('Token 有效', 'FAIL', str(e)[:150]))
        return out

    try:
        sc = api_get('/user/scopes', token=token, host='api.github.com')
        scopes = sc.get('scopes', [])
        out.append(('权限范围', 'OK' if 'repo' in scopes else 'WARN',
                    ','.join(scopes) if scopes else '(无 classic scope)'))
    except Exception:
        pass
    return out


if __name__ == '__main__':
    import sys
    print('=== GitHub 直连自检 ===')
    print('api.github.com 候选 IP:', resolve('api.github.com'))
    tok = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('GITHUB_TOKEN')
    if not tok:
        print('\n未提供 token，仅测试连通性')
        try:
            rate = api_get('/rate_limit')
            print('rate:', rate.get('rate'))
        except Exception as e:
            print('FAIL:', e)
    else:
        for name, st, detail in check(tok):
            print('  [%s] %-10s %s' % (st, name, detail))
