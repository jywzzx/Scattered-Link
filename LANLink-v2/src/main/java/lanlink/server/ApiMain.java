package lanlink.server;

/**
 * 无头入口：只起接口服务，不创建任何窗口。
 *
 * 与 lanlink.Main 的关系
 * ──────────────────────
 * Main 是 Swing 版的入口（提权 → 单开 → 起窗口），本类是同级的另一个前端入口。
 * 桌面端从 Electron 侧拉起时用：
 *
 *     Sparsa.exe --api            （打包后，走 jpackage app-image）
 *     java -cp classes lanlink.server.ApiMain --api
 *
 * 就绪信号通过 stdout 输出，父进程据此判定「后端可用」：
 *
 *     SPARSA_API_PORT=52311
 *     SPARSA_API_TOKEN=xxxxxxxx
 *
 * 之所以不走固定端口：端口被占用时固定端口会让程序直接起不来，
 * 而随机端口 + 主动上报永远能起来。
 */
public final class ApiMain {

    public static void main(String[] args) {
        int port = 0;                       // 0 = 由系统分配空闲端口
        String token = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--port".equals(a) && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("端口参数无效，改用系统分配");
                }
            } else if ("--token".equals(a) && i + 1 < args.length) {
                token = args[++i];
            }
        }

        if (token == null || token.isEmpty()) {
            token = ApiServer.randomToken();
        }

        ApiServer server = null;
        try {
            server = new ApiServer(port, token);
            server.start();
        } catch (Exception e) {
            System.err.println("接口服务启动失败：" + e);
            System.exit(2);
            return;
        }

        // 父进程靠这两行判定就绪，必须立即刷出，不能被缓冲区吞掉
        System.out.println("SPARSA_API_PORT=" + server.port());
        System.out.println("SPARSA_API_TOKEN=" + server.token());
        System.out.println("SPARSA_API_READY=1");
        System.out.flush();

        final ApiServer running = server;
        Runtime.getRuntime().addShutdownHook(new Thread(running::stop, "sparsa-api-shutdown"));
    }
}
