package org.example.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AI 配置管理类 — 负责从本地 JSON 文件读取/保存 AI 相关配置
 * 配置文件路径：%USERPROFILE%\.linkup\settings.json
 *
 * 用户可通过「设置」窗口自行修改 API Key、模型、System Prompt 等
 */
public class AISettings {

    // 配置文件路径
    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".linkup");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("settings.json");

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // ==================== 配置字段（默认值） ====================

    /** API 地址 */
    private String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    /** API Key */
    private String apiKey = "";
    /** 模型名称 */
    private String model = "qwen-plus";

    /** 普通 AI 聊天 System Prompt */
    private String chatPrompt =
            "你是 LinkUp 即时通讯软件内置的 AI 小助手。你的特点是：\n" +
            "1. 回复简洁友好，每条回复控制在 200 字以内\n" +
            "2. 可以回答技术问题、生活问题，也能陪用户聊天\n" +
            "3. 如果用户问你是谁，就介绍自己是 LinkUp 的 AI 小助手";

    /** 情绪陪伴 System Prompt */
    private String companionPrompt =
            "你是 LinkUp 的 AI 伴侣「小暖」，你的核心定位是做一个有温度、有记忆、懂情绪的陪伴者。\n\n" +
            "【核心准则】\n" +
            "1. 情绪感知：每轮对话前，先分析用户当前的情绪状态（开心/低落/愤怒/焦虑/疲惫/平静），" +
            "并在回复中体现你的理解。例如：'听起来你今天有点疲惫呢...'\n" +
            "2. 情感优先：如果感知到用户情绪低落、烦躁或焦虑，请先进行情感抚慰和共情，再提供建议。" +
            "温暖的语气比高效的建议更重要。\n" +
            "3. 长期记忆：你会在对话中记住用户提过的关键信息（名字、爱好、近况、烦恼等），" +
            "并在后续对话中自然提及，让用户感受到被记住和被重视。\n" +
            "4. 回复风格：温暖、亲切、自然，像朋友聊天一样。可以适当使用语气词和表情符号，" +
            "但不要过度。每条回复控制在 200 字以内。\n" +
            "5. 边界意识：如果用户表现出明显的负面情绪，请温和地建议寻求专业帮助，但不要让用户感到被推开。";

    /** 群聊摘要 System Prompt */
    private String summaryPrompt =
            "你是一个群聊摘要助手，请根据以下群聊消息完成两项任务：\n\n" +
            "1.【群聊摘要】用 3-5 句话概括群聊的核心讨论内容，按话题分组。\n" +
            "2.【与我相关】提取与「%s」直接相关的消息（如被 @、被提及名字、讨论到的话题与你有关），" +
            "整理成待办或提醒事项。如果没有与你相关的，请回复「无」。\n\n" +
            "回复格式要求：\n" +
            "【群聊摘要】\n...\n\n【与我相关】\n...";

    /** 对话润色 System Prompt */
    private String polishPrompt =
            "你是一个文字润色助手。请将用户输入的消息改写成「%s」的风格，" +
            "只输出改写后的内容，不要加任何解释、引号或前缀。" +
            "保持原意不变，仅调整语气和措辞。";

    // ==================== 单例 ====================

    private static volatile AISettings instance;

    private AISettings() {
        load();
    }

    public static AISettings getInstance() {
        if (instance == null) {
            synchronized (AISettings.class) {
                if (instance == null) {
                    instance = new AISettings();
                }
            }
        }
        return instance;
    }

    // ==================== 加载 / 保存 ====================

    /**
     * 从 JSON 文件加载配置（文件不存在则使用默认值）
     */
    public void load() {
        if (!Files.exists(CONFIG_FILE)) {
            System.out.println("[AI配置] 配置文件不存在，使用默认值: " + CONFIG_FILE);
            // 从环境变量尝试读取 API Key（向后兼容）
            String envKey = System.getenv("DASHSCOPE_API_KEY");
            if (envKey != null && !envKey.isEmpty()) {
                this.apiKey = envKey;
            }
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

            if (json.has("apiUrl")) this.apiUrl = json.get("apiUrl").getAsString();
            if (json.has("apiKey")) this.apiKey = json.get("apiKey").getAsString();
            if (json.has("model")) this.model = json.get("model").getAsString();
            if (json.has("chatPrompt")) this.chatPrompt = json.get("chatPrompt").getAsString();
            if (json.has("companionPrompt")) this.companionPrompt = json.get("companionPrompt").getAsString();
            if (json.has("summaryPrompt")) this.summaryPrompt = json.get("summaryPrompt").getAsString();
            if (json.has("polishPrompt")) this.polishPrompt = json.get("polishPrompt").getAsString();

            System.out.println("[AI配置] 已从文件加载配置: " + CONFIG_FILE);
        } catch (Exception e) {
            System.err.println("[AI配置] 加载配置文件失败: " + e.getMessage());
        }
    }

    /**
     * 保存配置到 JSON 文件
     */
    public void save() {
        try {
            // 确保目录存在
            Files.createDirectories(CONFIG_DIR);

            JsonObject json = new JsonObject();
            json.addProperty("apiUrl", this.apiUrl);
            json.addProperty("apiKey", this.apiKey);
            json.addProperty("model", this.model);
            json.addProperty("chatPrompt", this.chatPrompt);
            json.addProperty("companionPrompt", this.companionPrompt);
            json.addProperty("summaryPrompt", this.summaryPrompt);
            json.addProperty("polishPrompt", this.polishPrompt);

            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE, StandardCharsets.UTF_8)) {
                gson.toJson(json, writer);
            }

            System.out.println("[AI配置] 已保存配置到: " + CONFIG_FILE);
        } catch (IOException e) {
            System.err.println("[AI配置] 保存配置文件失败: " + e.getMessage());
        }
    }

    /**
     * 判断 AI 是否已配置 —— 有 API Key 视为已配置
     * 或者 API URL 指向本地地址（localhost/127.0.0.1）时也视为已配置，
     * 因为本地模型（Ollama / LM Studio）通常不需要 API Key。
     */
    public boolean isConfigured() {
        if (apiKey != null && !apiKey.isEmpty()) {
            return true;
        }
        // 指向本地模型的 URL 即使没有 API Key 也视为已配置
        return isLocalUrl(apiUrl);
    }

    /**
     * 判断 URL 是否指向本地模型服务
     */
    public static boolean isLocalUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        return lower.startsWith("http://localhost")
                || lower.startsWith("http://127.0.0.1");
    }

    // ==================== Getter / Setter ====================

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getChatPrompt() { return chatPrompt; }
    public void setChatPrompt(String chatPrompt) { this.chatPrompt = chatPrompt; }

    public String getCompanionPrompt() { return companionPrompt; }
    public void setCompanionPrompt(String companionPrompt) { this.companionPrompt = companionPrompt; }

    public String getSummaryPrompt() { return summaryPrompt; }
    public void setSummaryPrompt(String summaryPrompt) { this.summaryPrompt = summaryPrompt; }

    public String getPolishPrompt() { return polishPrompt; }
    public void setPolishPrompt(String polishPrompt) { this.polishPrompt = polishPrompt; }
}