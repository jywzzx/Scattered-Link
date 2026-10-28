'use strict';

const $ = (id) => document.getElementById(id);

const state = { data: null, lastLogKey: '' };

function fmtBytes(n) {
  if (!n) return '0 B';
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1073741824) return (n / 1048576).toFixed(2) + ' MB';
  return (n / 1073741824).toFixed(2) + ' GB';
}
function fmtRate(n) { return fmtBytes(n) + '/s'; }

function toast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.remove('show'), 1900);
}

async function api(path, body) {
  const opt = body
    ? { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
    : { method: 'GET' };
  const res = await fetch(path, opt);
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.error || `HTTP ${res.status}`);
  return json;
}

/* ---------------- 渲染 ---------------- */

function render(s) {
  state.data = s;

  // 状态胶囊
  const pill = $('statusPill');
  pill.className = 'pill';
  if (s.mode === 'host') {
    const online = s.tunnel && s.tunnel.peers.length > 0;
    pill.classList.add('pill-host');
    if (!online) pill.classList.add('pill-wait');
    pill.textContent = online ? `主机 · ${s.tunnel.peers.length} 人在线` : '主机 · 等待连接';
  } else if (s.mode === 'guest') {
    pill.classList.add('pill-guest');
    pill.textContent = s.tunnel && s.tunnel.established ? '已连接主机' : '连接中…';
  } else {
    pill.classList.add('pill-idle');
    pill.textContent = '未连接';
  }

  const up = s.uptime || 0;
  $('uptime').textContent = s.mode === 'idle'
    ? ''
    : `运行 ${Math.floor(up / 60)}分${up % 60}秒`;

  // 网络信息
  $('lanIp').textContent = s.lanIp || '--';
  $('v6Ip').textContent = s.globalIpv6 || '无公网 IPv6';

  const pub = s.public;
  if (pub && pub.ok && pub.mapped) {
    $('pubIp').textContent = `${pub.mapped.address}:${pub.mapped.port}`;
  } else {
    $('pubIp').textContent = pub && pub.reason ? '未获取' : '检测中…';
  }

  const nat = $('natType');
  const nt = pub && pub.natType;
  nat.className = 'tag';
  if (nt === 'CONE') { nat.classList.add('cond'); nat.textContent = '锥型 · 可打洞'; }
  else if (nt === 'SYMMETRIC') { nat.classList.add('sym'); nat.textContent = '对称型 · 打洞难'; }
  else if (nt === 'UNKNOWN') { nat.classList.add('unk'); nat.textContent = '未知'; }
  else nat.textContent = '--';

  // 网卡列表
  const list = $('ifaceList');
  if (s.ipv4 && s.ipv4.length) {
    list.innerHTML = s.ipv4.map((x) =>
      `<div><span>${x.iface}</span><span>${x.address} <em style="color:#94a3b8;font-style:normal">${x.kind}</em></span></div>`
    ).join('');
  } else {
    list.innerHTML = '<div><span>无可用网卡</span></div>';
  }

  // NAT 提示
  const hint = $('natHint');
  hint.className = 'hint';
  if (s.mode === 'host' && s.host) {
    const via = s.host.primary.via;
    if (via === '仅局域网') {
      hint.classList.add('warn');
      hint.textContent = '⚠ 未找到公网入口。请到路由器开启 UPnP，或确认宽带是否分配了公网 IPv4/IPv6。';
    } else if (via === 'IPv6直连') {
      hint.classList.add('ok');
      hint.textContent = '✓ 使用 IPv6 直连，速度最优。请确认朋友家也支持 IPv6。';
    } else {
      hint.classList.add('ok');
      hint.textContent = `✓ 已通过 ${via} 打通公网入口，朋友在任意网络都能连。`;
    }
  } else if (nt === 'SYMMETRIC') {
    hint.classList.add('warn');
    hint.textContent = '⚠ 检测到对称型 NAT（常见于移动网络/部分宽带），打洞成功率低。建议在路由器开启 UPnP 或使用 IPv6。';
  } else if (pub && pub.note) {
    hint.textContent = `提示：${pub.note}。若朋友连不上，优先在路由器开启 UPnP。`;
  } else if (s.mode === 'idle') {
    hint.textContent = '提示：双方都在 NAT 后时，需要路由器 UPnP 或公网 IPv6 才能直连。本机信息每 30 秒自动刷新。';
  }

  // 主机结果
  if (s.mode === 'host' && s.host) {
    $('hostResult').classList.remove('hidden');
    $('startHost').disabled = true;
    $('servicePort').disabled = true;
    $('connCode').textContent = s.host.code;
    $('keyHex').textContent = s.host.keyHex;
    $('tunnelPort').textContent = s.tunnel ? String(s.tunnel.localPort) : '--';
    $('viaTag').textContent = s.host.primary.via;

    $('epList').innerHTML = s.host.endpoints.map((e) => {
      const addr = e.family === 'IPv6' ? `[${e.address}]:${e.port}` : `${e.address}:${e.port}`;
      const warn = e.reliable === false ? ' style="opacity:.6"' : '';
      return `<li${warn}><span class="ep">${addr}</span><span class="via">${e.via}</span><button class="epcopy" data-copy="${addr}" title="复制地址">复制</button></li>`;
    }).join('');

    const peers = (s.tunnel && s.tunnel.peers) || [];
    $('peerCount').textContent = String(peers.length);
    $('peerList').innerHTML = peers.length
      ? peers.map((p) => `<li><span>${p.address}:${p.port}</span><span>${p.idle}s 前活跃</span></li>`).join('')
      : '<li class="empty">等待朋友输入连接码…</li>';
  } else if (s.mode !== 'host') {
    $('hostResult').classList.add('hidden');
    $('startHost').disabled = false;
    $('servicePort').disabled = false;
  }

  // 加入结果
  if (s.mode === 'guest' && s.guest) {
    $('joinResult').classList.remove('hidden');
    $('localAddr').textContent = s.guest.localAddress;
    $('doJoin').disabled = true;
    $('doJoinManual').disabled = true;
  } else if (s.mode !== 'guest') {
    $('joinResult').classList.add('hidden');
    $('doJoin').disabled = false;
    $('doJoinManual').disabled = false;
  }

  // 统计
  $('rateTx').textContent = fmtRate(s.rate.tx);
  $('rateRx').textContent = fmtRate(s.rate.rx);
  $('streamCount').textContent = String((s.tunnel && s.tunnel.streams.length) || 0);
  $('totals').textContent = `${fmtBytes(s.totals.tx)} ↑ / ${fmtBytes(s.totals.rx)} ↓`;
  $('hostname').textContent = `${s.hostname} · ${s.platform}`;

  renderLog(s.events || []);
}

function renderLog(events) {
  const box = $('log');
  const lastT = Number(box.dataset.lastT || 0);
  const fresh = events.filter((e) => Number(e.t) > lastT);
  if (!fresh.length) return;

  const frag = document.createDocumentFragment();
  for (const ev of fresh) {
    const row = document.createElement('div');
    row.className = 'row';
    const d = new Date(ev.t);
    const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
    row.innerHTML = `<span class="t">${time}</span><span class="${ev.level}"></span>`;
    row.lastChild.textContent = ev.text;
    frag.appendChild(row);
  }
  box.appendChild(frag);
  box.dataset.lastT = String(fresh[fresh.length - 1].t);
  box.scrollTop = box.scrollHeight;
  while (box.children.length > 200) box.removeChild(box.firstChild);
}

/* ---------------- 交互 ---------------- */

function switchTab(name) {
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
  document.querySelectorAll('.pane').forEach((p) => p.classList.toggle('active', p.id === 'pane-' + name));
}

document.querySelectorAll('.tab').forEach((t) => {
  t.addEventListener('click', () => switchTab(t.dataset.tab));
});

$('startHost').addEventListener('click', async () => {
  const port = Number($('servicePort').value);
  if (!port) return toast('请填写服务端口');
  const btn = $('startHost');
  btn.disabled = true;
  btn.textContent = '正在打通网络…';
  $('hostWarn').innerHTML = '';
  try {
    const r = await api('/api/host/start', { servicePort: port });
    toast(r.upnp ? '主机已启动，路由器已自动放行' : '主机已启动');
    if (!r.upnp && r.primary.via === '仅局域网') {
      $('hostWarn').innerHTML = '<div>当前只能局域网联机。请在路由器开启 UPnP，或确认宽带是否提供公网 IPv4/IPv6。</div>';
    }
  } catch (e) {
    toast(e.message);
    $('hostWarn').innerHTML = `<div>${e.message}</div>`;
  } finally {
    btn.textContent = '启动主机';
    btn.disabled = false;
  }
});

$('stopHost').addEventListener('click', async () => {
  await api('/api/host/stop', {}).catch(() => {});
  toast('已断开');
});

$('doJoin').addEventListener('click', async () => {
  const code = $('joinCode').value.trim();
  if (!code) return toast('请粘贴连接码');
  const btn = $('doJoin');
  btn.disabled = true;
  btn.textContent = '正在握手…';
  try {
    const r = await api('/api/join', { code });
    toast('连接成功');
    switchTab('join');
  } catch (e) {
    toast(e.message);
  } finally {
    btn.textContent = '连接';
    btn.disabled = false;
  }
});

$('doJoinManual').addEventListener('click', async () => {
  const body = {
    address: $('mAddress').value.trim(),
    tunnelPort: Number($('mPort').value),
    keyHex: $('mKey').value.trim(),
    servicePort: Number($('mService').value),
  };
  const btn = $('doJoinManual');
  btn.disabled = true;
  try {
    await api('/api/join', body);
    toast('连接成功');
  } catch (e) {
    toast(e.message);
  } finally {
    btn.disabled = false;
  }
});

$('leave').addEventListener('click', async () => {
  await api('/api/leave', {}).catch(() => {});
  toast('已断开');
});

$('copyCode').addEventListener('click', async () => {
  const code = $('connCode').textContent;
  await copyText(code, '连接码已复制');
});

$('epList').addEventListener('click', async (ev) => {
  const btn = ev.target.closest('.epcopy');
  if (!btn) return;
  await copyText(btn.dataset.copy, '地址已复制');
});

async function copyText(text, okMsg) {
  try {
    await navigator.clipboard.writeText(text);
    toast(okMsg);
  } catch (_) {
    // 非 HTTPS / 老浏览器降级：选中文本让用户手动复制
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); toast(okMsg); } catch (e) { toast('请手动复制'); }
    document.body.removeChild(ta);
  }
}

$('refreshNet').addEventListener('click', async () => {
  $('pubIp').textContent = '检测中…';
  try { await api('/api/net/refresh', {}); } catch (e) { toast(e.message); }
});

/* ---------------- SSE ---------------- */

let es = null;
function connectSSE() {
  if (es) es.close();
  es = new EventSource('/api/events');
  es.onmessage = (ev) => {
    try {
      const msg = JSON.parse(ev.data);
      if (msg.type === 'state') render(msg.state);
    } catch (_) {}
  };
  es.onerror = () => {
    es.close();
    setTimeout(connectSSE, 2500);
  };
}

api('/api/state').then(render).catch(() => {});
connectSSE();

// 兜底轮询（SSE 被代理切断时仍可用）
setInterval(() => {
  if (!es || es.readyState === 2) {
    api('/api/state').then(render).catch(() => {});
  }
}, 5000);
