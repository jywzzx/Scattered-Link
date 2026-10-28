#!/usr/bin/env node
'use strict';
const { App } = require('./server');
const { Logger } = require('./util');
const { exec } = require('node:child_process');

function openBrowser(url) {
  const cmd = process.platform === 'win32'
    ? `start "" "${url}"`
    : process.platform === 'darwin'
      ? `open "${url}"`
      : `xdg-open "${url}"`;
  exec(cmd, () => {});
}

function parseArgs(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) continue;
    const key = a.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith('--')) { out[key] = next; i++; }
    else out[key] = true;
  }
  return out;
}

async function main() {
  const args = parseArgs(process.argv);
  const log = new Logger('main', args.log || 'info');
  const app = new App({ httpPort: Number(args.port) || 7788, logger: log });

  const port = await app.listen();
  const url = `http://127.0.0.1:${port}/`;

  process.stdout.write('\n');
  process.stdout.write('  ╔══════════════════════════════════════════╗\n');
  process.stdout.write('  ║           LANLink  异地局域网联机         ║\n');
  process.stdout.write('  ╚══════════════════════════════════════════╝\n');
  process.stdout.write(`\n   控制台:  ${url}\n`);
  process.stdout.write(`   本机 IP: ${app.net.primaryLanIp}\n`);
  process.stdout.write(`   手机访问: http://${app.net.primaryLanIp}:${port}/  (同一 WiFi 下)\n`);
  process.stdout.write('\n   按 Ctrl+C 退出\n\n');

  if (args.open) setTimeout(() => openBrowser(url), 300);

  const shutdown = async () => {
    process.stdout.write('\n正在关闭...\n');
    try { await app.destroy(); } catch (_) {}
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
  process.on('uncaughtException', (e) => {
    log.error('未捕获异常:', e && e.stack ? e.stack : e);
  });
  process.on('unhandledRejection', (e) => {
    log.error('未处理的 Promise 拒绝:', e && e.stack ? e.stack : e);
  });
}

main().catch((e) => {
  process.stderr.write('启动失败: ' + (e && e.stack ? e.stack : e) + '\n');
  process.exit(1);
});
