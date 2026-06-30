package org.example.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * AI 助手模块 - 调用通义千问 OpenAPI 获取对话回复
 * 支持三种场景：普通聊天、群聊摘要、对话润色
 *
 * 配置方式：客户端「AI 空间」→「设置」按钮，或直接编辑 %USERPROFILE%\.linkup\settings.json
 * 申请地址: https://dashscope.console.aliyun.com/
 */
public class AIAssistant {

    // 超时配置
    // 注意：本地 AI 模型（如 Ollama / LM Studio）推理速度较慢，
    // readTimeout 设为 60 秒以避免群聊摘要和长对话超时失败
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)   // 连接超时 10 秒
            .readTimeout(60, TimeUnit.SECONDS)       // 读取超时 60 秒（本地模型推理慢，调长一倍）
            .writeTimeout(10, TimeUnit.SECONDS)      // 写入超时 10 秒
            .build();

    /**
     * 获取当前配置（每次调用时实时读取，确保用户修改后立即生效）
     */
    private static AISettings getSettings() {
        return AISettings.getInstance();
    }

    // ==================== 场景一：普通 AI 助手 ====================

    /**
     * 与 AI 对话（同步阻塞，请在独立线程中调用）
     * System Prompt 从 AISettings 中读取，用户可在设置界面自定义
     */
    public static String chat(String userMessage) throws IOException {
        AISettings settings = getSettings();
        if (!settings.isConfigured()) {
            return simulateReply(userMessage);
        }
        return callAPI(settings, settings.getChatPrompt(), userMessage);
    }

    // ==================== 场景 B：情绪陪伴 AI 伴侣 ====================

    /**
     * 情绪陪伴模式对话（同步阻塞，请在独立线程中调用）
     *
     * @param userMessage 用户输入的消息
     * @param context     对话上下文（历史消息摘要，用于长期记忆）
     * @return AI 伴侣的回复文本
     */
    public static String chatWithEmotion(String userMessage, String context) throws IOException {
        AISettings settings = getSettings();
        if (!settings.isConfigured()) {
            return simulateCompanionReply(userMessage);
        }
        // 将上下文拼接到用户自定义的 system prompt 中，让 AI 看到历史
        String fullPrompt = settings.getCompanionPrompt();
        if (context != null && !context.isEmpty()) {
            fullPrompt += "\n\n【用户近期对话摘要（你的长期记忆）】\n" + context;
        }
        return callAPI(settings, fullPrompt, userMessage);
    }

    // ==================== 场景 A：群聊摘要 ====================

    /**
     * 群聊摘要（同步阻塞，请在独立线程中调用）
     *
     * @param groupMessages 群聊消息列表，每行一条 "用户名: 消息内容"
     * @param currentUser   当前用户名，用于提取"与我相关"
     * @return 摘要结果
     */
    public static String summarizeGroup(String groupMessages, String currentUser) throws IOException {
        AISettings settings = getSettings();
        if (!settings.isConfigured()) {
            return simulateSummary(groupMessages, currentUser);
        }
        String fullPrompt = String.format(settings.getSummaryPrompt(), currentUser);
        return callAPI(settings, fullPrompt, groupMessages);
    }

    // ==================== 场景 C：对话润色 ====================

    /**
     * 私聊对话摘要（同步阻塞，请在独立线程中调用）
     * 与群聊摘要不同，私聊摘要侧重于理解对话主题和关键信息
     *
     * @param chatMessages 私聊消息列表，每行一条 "用户名: 消息内容"
     * @param targetUser   私聊对象用户名
     * @param currentUser  当前用户名
     * @return 摘要结果
     */
    public static String summarizeChat(String chatMessages, String targetUser, String currentUser) throws IOException {
        AISettings settings = getSettings();
        if (!settings.isConfigured()) {
            return "【模拟摘要】与 " + targetUser + " 的对话摘要（请配置 AI 服务以启用真实摘要）";
        }
        String systemPrompt = String.format(settings.getChatSummaryPrompt(), targetUser);
        return callAPI(settings, systemPrompt, chatMessages);
    }

    // ==================== 场景 C：对话润色 ====================

    /**
     * 对话润色（同步阻塞，请在独立线程中调用）
     *
     * @param originalText 原始消息文本
     * @param style        润色风格：高情商 / 专业 / 委婉
     * @return 润色后的文本
     */
    public static String polish(String originalText, String style) throws IOException {
        AISettings settings = getSettings();
        if (!settings.isConfigured()) {
            return simulatePolish(originalText, style);
        }
        String fullPrompt = String.format(settings.getPolishPrompt(), style);
        return callAPI(settings, fullPrompt, originalText);
    }

    // ==================== API 调用核心方法 ====================

    /**
     * 调用 AI API（支持任意兼容 OpenAI 格式的服务）
     */
    private static String callAPI(AISettings settings, String systemPrompt, String userMessage) throws IOException {
        // 自动补全 API URL：如果用户只填了 base URL（如 http://localhost:1234），
        // 自动追加 /v1/chat/completions，避免 LM Studio / Ollama 等本地模型报 "POST /" 错误
        String apiUrl = settings.getApiUrl();
        // 去掉末尾可能存在的斜杠
        apiUrl = apiUrl.replaceAll("/+$", "");
        // 已包含完整路径，无需补全
        if (!apiUrl.endsWith("/v1/chat/completions") && !apiUrl.contains("/chat/completions")) {
            if (apiUrl.endsWith("/v1")) {
                // e.g., http://localhost:1234/v1 → http://localhost:1234/v1/chat/completions
                apiUrl = apiUrl + "/chat/completions";
            } else {
                // e.g., http://localhost:1234 → http://localhost:1234/v1/chat/completions
                apiUrl = apiUrl + "/v1/chat/completions";
            }
            System.out.println("[AI] URL 自动补全: " + settings.getApiUrl() + " -> " + apiUrl);
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", settings.getModel());

        JsonArray messages = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        requestBody.add("messages", messages);

        RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + settings.getApiKey())
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "无详细错误";
                throw new IOException("AI API 返回错误 " + response.code() + ": " + errorBody);
            }

            String responseBody = response.body().string();
            JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();

            JsonArray choices = jsonResponse.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject firstChoice = choices.get(0).getAsJsonObject();
                JsonObject message = firstChoice.getAsJsonObject("message");
                return message.get("content").getAsString();
            }
            return "AI 助手没有返回有效回复";
        }
    }

    // ==================== 模拟回复（未配置 API Key 时使用） ====================

    private static String simulateReply(String userMessage) {
        String msg = userMessage.toLowerCase();
        if (msg.contains("你好") || msg.contains("hi") || msg.contains("hello")) {
            return "你好！我是 LinkUp 的 AI 小助手，有什么可以帮你的吗？";
        } else if (msg.contains("你是谁") || msg.contains("介绍")) {
            return "我是 LinkUp 即时通讯软件内置的 AI 小助手，可以回答技术问题、陪你聊天，随时为你服务！";
        } else if (msg.contains("谢谢") || msg.contains("感谢")) {
            return "不客气！很高兴能帮到你。";
        } else if (msg.contains("再见") || msg.contains("拜拜")) {
            return "再见！期待下次聊天，祝你生活愉快！";
        } else {
            return "收到了你的消息。我是 LinkUp AI 助手，目前处于离线模拟模式。配置 API Key 后可以获得更智能的回复！";
        }
    }

    /** 模拟情绪陪伴回复 */
    private static String simulateCompanionReply(String userMessage) {
        String msg = userMessage.toLowerCase();
        if (msg.contains("累") || msg.contains("疲惫") || msg.contains("压力")) {
            return "听起来你最近真的很辛苦呢。累的时候不用硬撑，给自己一点喘息的时间。"
                    + "要不要和我聊聊发生了什么？我一直在这里陪着你。";
        } else if (msg.contains("难过") || msg.contains("伤心") || msg.contains("不开心")) {
            return "我能感受到你的心情有些低落。有时候把心里话说出来会好受一些，"
                    + "你想说什么都可以，我会认真听的。";
        } else if (msg.contains("开心") || msg.contains("高兴") || msg.contains("快乐")) {
            return "真好！看到你开心我也跟着开心起来了。分享快乐会让快乐加倍呢，"
                    + "快跟我说说发生了什么好事？";
        } else {
            return "我在这里呢。不管你想聊天、倾诉，还是单纯想找人说说话，我都陪着你。";
        }
    }

    /** 模拟群聊摘要 */
    private static String simulateSummary(String groupMessages, String currentUser) {
        return "【群聊摘要】\n模拟模式：群聊中包含 " + groupMessages.split("\n").length
                + " 条消息。配置 AI API Key 后可获得智能摘要。\n\n【与我相关】\n无";
    }

    /** 模拟对话润色 */
    private static String simulatePolish(String originalText, String style) {
        return "[" + style + "润色] " + originalText + "（模拟模式，配置 API Key 后生效）";
    }
}