package org.example.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 消息协议工具类 - 定义客户端与服务端之间的 JSON 通信协议
 * 所有消息均以换行符 \n 分隔，方便逐行读取
 *
 * 协议格式（JSON）：
 * {
 *   "type": "消息类型",
 *   "sender": "发送者用户名",
 *   "receiver": "接收者（用户名/群ID/AI小助手）",
 *   "content": "消息内容",
 *   "msgId": "消息唯一ID（用于去重/撤回/状态追踪）",
 *   "time": "时间戳（服务端填充）"
 * }
 */
public class MessageProtocol {

    private static final Gson gson = new Gson();

    // ==================== 原有消息类型 ====================
    public static final String TYPE_LOGIN          = "LOGIN";
    public static final String TYPE_REGISTER       = "REGISTER";
    public static final String TYPE_PRIVATE_CHAT   = "PRIVATE_CHAT";
    public static final String TYPE_GROUP_CHAT     = "GROUP_CHAT";
    public static final String TYPE_AI_CHAT        = "AI_CHAT";
    public static final String TYPE_AI_COMPANION   = "AI_COMPANION";
    public static final String TYPE_GROUP_SUMMARY  = "GROUP_SUMMARY";
    public static final String TYPE_POLISH         = "POLISH";
    public static final String TYPE_ADD_FRIEND     = "ADD_FRIEND";
    public static final String TYPE_REMOVE_FRIEND  = "REMOVE_FRIEND";
    public static final String TYPE_LOGIN_SUCCESS  = "LOGIN_SUCCESS";
    public static final String TYPE_LOGIN_FAIL     = "LOGIN_FAIL";
    public static final String TYPE_REGISTER_SUCCESS = "REGISTER_SUCCESS";
    public static final String TYPE_REGISTER_FAIL  = "REGISTER_FAIL";
    public static final String TYPE_USER_LIST      = "USER_LIST";
    public static final String TYPE_GROUP_LIST     = "GROUP_LIST";
    public static final String TYPE_MESSAGE        = "MESSAGE";
    public static final String TYPE_HISTORY        = "HISTORY";
    public static final String TYPE_ERROR          = "ERROR";
    public static final String TYPE_LOGOUT         = "LOGOUT";
    public static final String TYPE_POLISH_RESULT  = "POLISH_RESULT";
    public static final String TYPE_USER_ONLINE    = "USER_ONLINE";
    public static final String TYPE_USER_OFFLINE   = "USER_OFFLINE";
    public static final String TYPE_ADD_FRIEND_SUCCESS = "ADD_FRIEND_SUCCESS";
    public static final String TYPE_ADD_FRIEND_FAIL    = "ADD_FRIEND_FAIL";
    public static final String TYPE_REFRESH_FRIENDS    = "REFRESH_FRIENDS";
    public static final String TYPE_FRIEND_LIST       = "FRIEND_LIST";
    public static final String TYPE_DELETE_MESSAGE    = "DELETE_MESSAGE";

    // 好友验证相关
    public static final String TYPE_FRIEND_REQUEST           = "FRIEND_REQUEST";
    public static final String TYPE_FRIEND_REQUEST_NOTIFY    = "FRIEND_REQUEST_NOTIFY";
    public static final String TYPE_FRIEND_REQUEST_ACCEPT    = "FRIEND_REQUEST_ACCEPT";
    public static final String TYPE_FRIEND_REQUEST_REJECT    = "FRIEND_REQUEST_REJECT";

    // ==================== P0: 心跳保活 + 断线重连 ====================
    public static final String TYPE_PING  = "PING";   // 客户端→服务端 心跳
    public static final String TYPE_PONG  = "PONG";   // 服务端→客户端 心跳响应

    // ==================== P0: 消息送达状态 + 去重 ====================
    public static final String TYPE_MSG_ACK       = "MSG_ACK";        // 服务端→发送方：消息已收到
    public static final String TYPE_MSG_DELIVERED = "MSG_DELIVERED";  // 服务端→发送方：消息已送达对方
    public static final String TYPE_MSG_READ      = "MSG_READ";       // 接收方→服务端→发送方：消息已读

    // ==================== P0: 离线消息推送 ====================
    public static final String TYPE_UNREAD_MESSAGES = "UNREAD_MESSAGES"; // 服务端→客户端：推送离线/未读消息

    // ==================== P1: 消息撤回 ====================
    public static final String TYPE_RECALL = "RECALL"; // 撤回消息（客户端→服务端→对方）

    // ==================== P1: 群组管理 ====================
    public static final String TYPE_CREATE_GROUP  = "CREATE_GROUP";   // 创建群组
    public static final String TYPE_JOIN_GROUP    = "JOIN_GROUP";     // 加入群组
    public static final String TYPE_LEAVE_GROUP   = "LEAVE_GROUP";    // 退出群组
    public static final String TYPE_KICK_MEMBER   = "KICK_MEMBER";    // 踢出成员
    public static final String TYPE_GROUP_LIST_REQ = "GROUP_LIST_REQ"; // 请求群组列表
    public static final String TYPE_GROUP_LIST_RESP = "GROUP_LIST_RESP"; // 群组列表响应
    public static final String TYPE_GROUP_MEMBERS  = "GROUP_MEMBERS";  // 群成员列表

    // ==================== P1: @提及 ====================
    public static final String TYPE_MENTION_NOTIFY = "MENTION_NOTIFY"; // @提及通知

    // ==================== P1: 黑名单 ====================
    public static final String TYPE_BLOCK_USER   = "BLOCK_USER";    // 拉黑用户
    public static final String TYPE_UNBLOCK_USER = "UNBLOCK_USER";  // 取消拉黑

    // ==================== P1: 消息搜索 ====================
    public static final String TYPE_SEARCH_MESSAGES = "SEARCH_MESSAGES"; // 搜索消息
    public static final String TYPE_SEARCH_RESULT   = "SEARCH_RESULT";   // 搜索结果

    // ==================== P1: AI 聊天总结 ====================
    public static final String TYPE_CHAT_SUMMARY = "CHAT_SUMMARY"; // 聊天摘要（进入会话时自动总结）

    // ==================== P0: 图片发送 ====================
    public static final String TYPE_IMAGE = "IMAGE"; // 图片消息

    // ==================== 工具方法 ====================

    /**
     * 生成全局唯一消息 ID（用于去重、撤回、状态追踪）
     */
    public static String generateMsgId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 将 JsonObject 序列化为字符串，末尾添加换行符
     */
    public static String toWire(JsonObject json) {
        return gson.toJson(json) + "\n";
    }

    /**
     * 从 JSON 字符串解析为 JsonObject
     */
    public static JsonObject fromWire(String jsonStr) {
        return gson.fromJson(jsonStr, JsonObject.class);
    }

    /**
     * 构建基础消息对象（自动生成 msgId）
     */
    public static JsonObject buildMessage(String type, String sender, String receiver, String content) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.addProperty("sender", sender);
        json.addProperty("receiver", receiver);
        json.addProperty("content", content);
        json.addProperty("msgId", generateMsgId());
        json.addProperty("time", String.valueOf(System.currentTimeMillis()));
        return json;
    }

    /**
     * 构建带指定 msgId 的消息对象（用于回复/ACK等场景）
     */
    public static JsonObject buildMessageWithId(String type, String sender, String receiver,
                                                 String content, String msgId) {
        JsonObject json = buildMessage(type, sender, receiver, content);
        json.addProperty("msgId", msgId);
        return json;
    }

    /**
     * 快速构建简单响应消息
     */
    public static String buildResponse(String type, String content) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.addProperty("sender", "server");
        json.addProperty("content", content);
        return toWire(json);
    }
}