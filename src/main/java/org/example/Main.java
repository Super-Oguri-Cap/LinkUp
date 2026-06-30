package org.example;

import org.example.client.LinkUpClient;
import org.example.server.LinkUpServer;

/**
 * LinkUp 即时通讯软件 - 统一启动入口
 *
 * 命令行参数：
 *   server   - 启动服务端
 *   client   - 启动客户端
 *
 * 前置条件：
 * - MySQL 数据库已创建 linkup 库，并执行了 db/ 目录下的建表脚本
 * - 修改 DBUtil.java 中的数据库连接配置
 * - （可选）配置环境变量 DASHSCOPE_API_KEY 以启用 AI 助手
 */
public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String mode = args[0].toLowerCase();
        switch (mode) {
            case "server":
                System.out.println("[LinkUp] 启动服务端...");
                LinkUpServer.main(args);
                break;
            case "client":
                System.out.println("[LinkUp] 启动客户端...");
                LinkUpClient.main(args);
                break;
            default:
                System.out.println("[LinkUp] 未知模式: " + mode);
                printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("============================================");
        System.out.println("  LinkUp 即时通讯软件");
        System.out.println("============================================");
        System.out.println("用法:");
        System.out.println("  java -jar LinkUp.jar server   启动服务端");
        System.out.println("  java -jar LinkUp.jar client   启动客户端");
        System.out.println("============================================");
    }
}