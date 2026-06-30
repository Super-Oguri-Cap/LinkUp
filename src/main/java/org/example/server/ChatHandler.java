package org.example.server;

import com.google.gson.JsonObject;
import org.example.service.ChatSessionManager;
import org.example.util.MessageProtocol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天消息处理器 - 处理私聊、群聊、AI聊天、图片消息
 * 从 ClientHandler 拆分，负责消息收发和转发逻辑
 */
public class ChatHandler implements MessageHandler {

    @Override
    public void handle(JsonObject msg, HandlerContext ctx) {
        String type = msg.get("type").getAsString();
        switch (type) {
            case MessageProtocol.TYPE_PRIVATE_CHAT:
                handlePrivateChat(msg, ctx);
                break;
            case MessageProtocol.TYPE_GROUP_CHAT:
                handleGroupChat(msg, ctx);
                break;
            case MessageProtocol.TYPE_AI_CHAT:
                handleAIChat(msg, ctx);
                break;
            case MessageProtocol.TYPE_AI_COMPANION:
                handleAICompanion(msg, ctx);
                break;
            case MessageProtocol.TYPE_GROUP_SUMMARY:
                handleGroupSummary(msg, ctx);
                break;
            case MessageProtocol.TYPE_POLISH:
                handlePolish(msg, ctx);
                break;
            case MessageProtocol.TYPE_IMAGE:
                handleImageMessage(msg, ctx);
                break;
        }
    }

    /**
     * 处理私聊消息：存储到数据库，发送 ACK，转发给目标用户
     */
    private void handlePrivateChat(JsonObject msg, HandlerContext ctx) {
        String receiver = msg.get("receiver").getAsString();
        String content = msg.get("content").getAsString();
        long timestamp = Long.parseLong(msg.get("time").getAsString());
        String msgId = msg.has("msgId") ? msg.get("msgId").getAsString() : "";
        String username = ctx.getUsername();

        // 检查接收者是否存在
        if (!ctx.userExists(receiver)) {
            ctx.sendError("消息发送失败：用户不存在");
            return;
        }

        // 消息去重检查
        if (!ctx.checkAndAddMsgId(msgId)) {
            System.out.println("[私聊] 重复消息，跳过: msgId=" + msgId);
            return;
        }

        // 检查发送方是否被接收方拉黑
        if (ctx.isBlocked(username, receiver)) {
            ctx.sendError("消息发送失败：你已被对方拉黑");
            return;
        }

        // 存储消息到数据库
        ctx.saveMessage(username, receiver, HandlerContext.CHAT_TYPE_PRIVATE,
                HandlerContext.MSG_TYPE_TEXT, content, timestamp);

        // 发送 ACK 回执给发送方
        if (!msgId.isEmpty()) {
            ctx.sendRaw("{\"type\":\"MSG_ACK\",\"sender\":\"server\",\"content\":\"" + msgId + "\"}\n");
        }

        // 回显给发送者
        JsonObject echo = MessageProtocol.buildMessageWithId(
                MessageProtocol.TYPE_MESSAGE, username, receiver, content, msgId);
        echo.addProperty("time", String.valueOf(timestamp));
        ctx.sendRaw(MessageProtocol.toWire(echo));

        // 转发给目标用户
        ClientHandler target = ctx.getServer().getClient(receiver);
        if (target != null) {
            JsonObject forward = MessageProtocol.buildMessageWithId(
                    MessageProtocol.TYPE_MESSAGE, username, receiver, content, msgId);
            forward.addProperty("time", String.valueOf(timestamp));
            target.sendRaw(MessageProtocol.toWire(forward));

            // 发送 DELIVERED 回执
            if (!msgId.isEmpty()) {
                ctx.sendRaw("{\"type\":\"MSG_DELIVERED\",\"sender\":\"server\",\"content\":\"" + msgId + "\"}\n");
            }
        }
    }

    /**
     * 处理群聊消息：广播给所有在线用户，持久化到数据库
     */
    private void handleGroupChat(JsonObject msg, HandlerContext ctx) {
        String content = msg.get("content").getAsString();
        long timestamp = Long.parseLong(msg.get("time").getAsString());
        String msgId = msg.has("msgId") ? msg.get("msgId").getAsString() : "";
        String username = ctx.getUsername();

        if (!ctx.checkAndAddMsgId(msgId)) {
            System.out.println("[群聊] 重复消息，跳过: msgId=" + msgId);
            return;
        }

        ctx.saveGroupMessage(username, content, timestamp);

        JsonObject broadcast = MessageProtocol.buildMessageWithId(
                MessageProtocol.TYPE_MESSAGE, username,
                ChatSessionManager.TARGET_ALL, content, msgId);
        broadcast.addProperty("time", String.valueOf(timestamp));
        ctx.getServer().broadcast(MessageProtocol.toWire(broadcast));
        System.out.println("[群聊] " + username + ": " + content);

        // @提及检测
        handleMentions(content, username, ctx);
    }

    /**
     * 处理 AI 聊天请求
     */
    private void handleAIChat(JsonObject msg, HandlerContext ctx) {
        final String content = msg.get("content").getAsString();
        final long timestamp = Long.parseLong(msg.get("time").getAsString());
        final String username = ctx.getUsername();

        JsonObject echo = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_MESSAGE, username,
                ChatSessionManager.TARGET_AI_ASSISTANT, content);
        echo.addProperty("time", String.valueOf(timestamp));
        ctx.sendRaw(MessageProtocol.toWire(echo));

        ctx.getServer().getExecutorService().submit(() -> {
            try {
                String aiReply = AIAssistant.chat(content);
                JsonObject reply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE,
                        ChatSessionManager.TARGET_AI_ASSISTANT, username, aiReply);
                reply.addProperty("time", String.valueOf(System.currentTimeMillis()));
                ctx.sendRaw(MessageProtocol.toWire(reply));
            } catch (Exception e) {
                JsonObject errorReply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE,
                        ChatSessionManager.TARGET_AI_ASSISTANT, username,
                        "AI 助手暂时不可用，请稍后再试");
                errorReply.addProperty("time", String.valueOf(System.currentTimeMillis()));
                ctx.sendRaw(MessageProtocol.toWire(errorReply));
                System.err.println("[AI] 调用失败: " + e.getMessage());
            }
        });
    }

    /**
     * 处理 AI 情绪陪伴请求
     */
    private void handleAICompanion(JsonObject msg, HandlerContext ctx) {
        final String content = msg.get("content").getAsString();
        final long timestamp = Long.parseLong(msg.get("time").getAsString());
        final String username = ctx.getUsername();

        JsonObject echo = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_MESSAGE, username,
                ChatSessionManager.TARGET_AI_COMPANION, content);
        echo.addProperty("time", String.valueOf(timestamp));
        ctx.sendRaw(MessageProtocol.toWire(echo));

        ctx.getServer().getExecutorService().submit(() -> {
            try {
                String context = ctx.loadRecentChatContext(username,
                        ChatSessionManager.TARGET_AI_COMPANION, 10);
                String aiReply = AIAssistant.chatWithEmotion(content, context);
                JsonObject reply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE,
                        ChatSessionManager.TARGET_AI_COMPANION, username, aiReply);
                reply.addProperty("time", String.valueOf(System.currentTimeMillis()));
                ctx.sendRaw(MessageProtocol.toWire(reply));
            } catch (Exception e) {
                JsonObject errorReply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE,
                        ChatSessionManager.TARGET_AI_COMPANION, username,
                        "AI 伴侣暂时不可用，请稍后再试");
                errorReply.addProperty("time", String.valueOf(System.currentTimeMillis()));
                ctx.sendRaw(MessageProtocol.toWire(errorReply));
                System.err.println("[AI伴侣] 调用失败: " + e.getMessage());
            }
        });
    }

    /**
     * 处理群聊摘要请求
     */
    private void handleGroupSummary(JsonObject msg, HandlerContext ctx) {
        final String username = ctx.getUsername();
        ctx.getServer().getExecutorService().submit(() -> {
            try {
                String groupMessages = ctx.loadRecentGroupMessages(20);
                String summary = AIAssistant.summarizeGroup(groupMessages, username);
                JsonObject reply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE,
                        ChatSessionManager.TARGET_AI_ASSISTANT, username, summary);
                reply.addProperty("time", String.valueOf(System.currentTimeMillis()));
                ctx.sendRaw(MessageProtocol.toWire(reply));
            } catch (Exception e) {
                JsonObject errorReply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE,
                        ChatSessionManager.TARGET_AI_ASSISTANT, username,
                        "群聊摘要生成失败: " + e.getMessage());
                errorReply.addProperty("time", String.valueOf(System.currentTimeMillis()));
                ctx.sendRaw(MessageProtocol.toWire(errorReply));
                System.err.println("[群聊摘要] 生成失败: " + e.getMessage());
            }
        });
    }

    /**
     * 处理对话润色请求
     */
    private void handlePolish(JsonObject msg, HandlerContext ctx) {
        final String contentStr = msg.get("content").getAsString();
        final String username = ctx.getUsername();
        final JsonObject content;
        final String originalText;
        final String style;
        try {
            content = MessageProtocol.fromWire(contentStr);
            originalText = content.get("text").getAsString();
            style = content.get("style").getAsString();
        } catch (Exception e) {
            ctx.sendError("润色请求格式错误: " + e.getMessage());
            return;
        }

        ctx.getServer().getExecutorService().submit(() -> {
            try {
                String polished = AIAssistant.polish(originalText, style);
                JsonObject reply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_POLISH_RESULT,
                        ChatSessionManager.TARGET_AI_ASSISTANT, username, polished);
                reply.addProperty("time", String.valueOf(System.currentTimeMillis()));
                ctx.sendRaw(MessageProtocol.toWire(reply));
            } catch (Exception e) {
                ctx.sendError("润色失败: " + e.getMessage());
            }
        });
    }

    /**
     * 处理图片消息
     */
    private void handleImageMessage(JsonObject msg, HandlerContext ctx) {
        String receiver = msg.get("receiver").getAsString();
        String content = msg.get("content").getAsString();
        long timestamp = Long.parseLong(msg.get("time").getAsString());
        String msgId = msg.has("msgId") ? msg.get("msgId").getAsString() : "";
        String username = ctx.getUsername();

        if (!ctx.checkAndAddMsgId(msgId)) {
            System.out.println("[图片] 重复消息，跳过: msgId=" + msgId);
            return;
        }

        ctx.saveMessage(username, receiver, HandlerContext.CHAT_TYPE_PRIVATE,
                HandlerContext.MSG_TYPE_IMAGE, content, timestamp);

        JsonObject echo = MessageProtocol.buildMessageWithId(
                MessageProtocol.TYPE_IMAGE, username, receiver, content, msgId);
        echo.addProperty("time", String.valueOf(timestamp));
        ctx.sendRaw(MessageProtocol.toWire(echo));

        ClientHandler target = ctx.getServer().getClient(receiver);
        if (target != null) {
            JsonObject forward = MessageProtocol.buildMessageWithId(
                    MessageProtocol.TYPE_IMAGE, username, receiver, content, msgId);
            forward.addProperty("time", String.valueOf(timestamp));
            target.sendRaw(MessageProtocol.toWire(forward));
        }
    }

    /**
     * @提及检测
     */
    private void handleMentions(String content, String sender, HandlerContext ctx) {
        Pattern pattern = Pattern.compile("@(\\w+)");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String mentionedUser = matcher.group(1);
            if (mentionedUser.equals(sender)) continue;
            ClientHandler mentioned = ctx.getServer().getClient(mentionedUser);
            if (mentioned != null) {
                JsonObject notify = new JsonObject();
                notify.addProperty("type", MessageProtocol.TYPE_MENTION_NOTIFY);
                notify.addProperty("sender", sender);
                notify.addProperty("receiver", ChatSessionManager.TARGET_GROUP_HALL);
                notify.addProperty("content", sender + " 在群聊中@了你");
                mentioned.sendRaw(MessageProtocol.toWire(notify));
                System.out.println("[@提及] " + sender + " @了 " + mentionedUser);
            }
        }
    }
}
