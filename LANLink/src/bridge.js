'use strict';
/**
 * TCP socket <-> ReliableStream 双向泵，支持半关闭。
 *   - socket 可读结束 → 只关隧道的写方向（发 FIN），仍能继续收
 *   - 隧道读结束   → 只关 socket 的写方向，等对端把数据收完再 destroy
 * 下行做简单背压：隧道积压超过水位就 pause 源 socket。
 */
const HIGH_WATER = 190 * 1024;

function bridgeTcp(socket, stream, log) {
  let sockDone = false;
  let streamDone = false;

  const finish = () => {
    if (sockDone && streamDone) {
      try { socket.destroy(); } catch (_) {}
      stream.destroy('bridge-done');
    }
  };

  socket.setNoDelay?.(true);

  socket.on('error', (e) => {
    log.debug('TCP socket 错误:', e.message);
    sockDone = true;
    stream.destroy('socket-error'); // 出错直接 RST，不必优雅关闭
  });

  socket.on('close', () => {
    sockDone = true;
    stream.end();      // 优雅收尾，不丢已在途的数据
    finish();
  });

  socket.on('data', (chunk) => {
    stream.write(chunk);
    if (stream.buffered > HIGH_WATER && socket.pause) socket.pause();
  });

  socket.on('end', () => stream.end());

  stream.on('drain', () => {
    if (socket.isPaused && socket.isPaused()) socket.resume();
  });

  stream.on('data', (chunk) => {
    socket.write(chunk);
  });

  stream.on('end', () => {
    streamDone = true;
    try { socket.end(); } catch (_) {}
    finish();
  });

  stream.on('close', (why) => {
    streamDone = true;
    log.debug('桥接关闭:', why);
    try { socket.destroy(); } catch (_) {}
  });

  return { HIGH_WATER };
}

module.exports = { bridgeTcp, HIGH_WATER };
