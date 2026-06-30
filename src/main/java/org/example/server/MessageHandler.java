package org.example.server;

import com.google.gson.JsonObject;

/**
 * 消息处理器接口（策略模式）
 * 每种消息类型对应一个独立的处理器实现，替代 ClientHandler 中庞大的 switch-case
 *
 * 改进说明：
 * 原 ClientHandler 在一个 1700+ 行的类中用 switch-case 处理所有消息类型，
 * 导致类过于臃肿、难以维护。现拆分为：
 * - AuthHandler: 登录/注册
 * - ChatHandler: 私聊/群聊/AI聊天
 * - FriendHandler: 好友请求/删除/刷新
 * - GroupHandler: 群组管理
 * - MessageHandler: 消息状态/撤回/删除/搜索
 * - SystemHandler: 心跳/登出/黑名单
 */
public interface MessageHandler {

    /**
     * 处理消息
     *
     * @param msg    消息 JSON 对象
     * @param ctx    处理器上下文（提供对 ClientHandler 的访问）
     */
    void handle(JsonObject msg, HandlerContext ctx);
}
