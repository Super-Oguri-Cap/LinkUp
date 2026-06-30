package org.example.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 聊天记录持久化服务
 * 负责聊天记录文件的路径计算、读取、写入和格式转换
 * 不依赖任何 Swing 组件，可独立进行单元测试
 */
public class ChatHistoryService {

    /** 聊天记录文件存放目录（项目根目录下的 chat_history） */
    private static final Path CHAT_HISTORY_DIR = Paths.get(
            System.getProperty("user.dir"), "chat_history");

    /** 文件内时间戳格式 */
    private static final SimpleDateFormat FILE_SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * 追加一条消息到对应的聊天记录文件
     *
     * @param chatType    聊天类型：GROUP, PRIVATE, AI, COMPANION
     * @param currentUser 当前用户名
     * @param target      聊天对象
     * @param sender      消息发送者
     * @param content     消息内容
     * @param timeStr     时间戳字符串（毫秒）
     */
    public void appendMessage(String chatType, String currentUser, String target,
                              String sender, String content, String timeStr) {
        try {
            Files.createDirectories(CHAT_HISTORY_DIR);
            Path file = getHistoryFile(chatType, currentUser, target);

            String readableTime = formatTime(timeStr);
            String safeContent = escapeContent(content);
            String line = readableTime + "\t" + sender + "\t" + safeContent + "\n";

            Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[ChatHistoryService] 写入聊天记录失败: " + e.getMessage());
        }
    }

    /**
     * 加载最近 N 条聊天记录
     *
     * @param chatType    聊天类型
     * @param currentUser 当前用户名
     * @param target      聊天对象
     * @param limit       加载条数上限
     * @return 消息记录列表，每条记录为 [时间, 发送者, 内容]
     */
    public List<String[]> loadRecentMessages(String chatType, String currentUser,
                                              String target, int limit) {
        List<String[]> result = new ArrayList<>();
        try {
            Path file = getHistoryFile(chatType, currentUser, target);
            if (!Files.exists(file)) {
                return result;
            }

            List<String> allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = Math.max(0, allLines.size() - limit);
            for (int i = start; i < allLines.size(); i++) {
                String line = allLines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                if (parts.length == 3) {
                    parts[2] = unescapeContent(parts[2]);
                    result.add(parts);  // [时间, 发送者, 内容]
                }
            }
        } catch (IOException e) {
            System.err.println("[ChatHistoryService] 读取聊天记录失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 删除指定的一条聊天记录
     * 根据时间戳 + 发送者 + 内容匹配，删除文件中的对应行
     *
     * @param chatType    聊天类型
     * @param currentUser 当前用户名
     * @param target      聊天对象
     * @param sender      消息发送者
     * @param content     消息内容
     * @param timeStr     时间戳字符串（毫秒）
     * @return 是否成功删除
     */
    public boolean deleteMessage(String chatType, String currentUser, String target,
                                  String sender, String content, String timeStr) {
        try {
            Path file = getHistoryFile(chatType, currentUser, target);
            if (!Files.exists(file)) {
                return false;
            }

            List<String> allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String readableTime = formatTime(timeStr);
            String safeContent = escapeContent(content);
            String targetLine = readableTime + "\t" + sender + "\t" + safeContent;

            // 找到匹配的行并删除
            boolean removed = allLines.removeIf(line -> line.trim().equals(targetLine));

            if (removed) {
                // 重写文件
                Files.write(file, allLines, StandardCharsets.UTF_8);
                System.out.println("[ChatHistoryService] 已删除消息: " + targetLine);
            }
            return removed;
        } catch (IOException e) {
            System.err.println("[ChatHistoryService] 删除消息失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 根据聊天类型和对象获取对应的聊天记录文件路径
     * 私聊：两个用户名按字典序排列，保证同一对话文件名一致
     * 群聊/AI：直接用类型标识
     */
    private Path getHistoryFile(String chatType, String currentUser, String target) {
        String filename;
        switch (chatType) {
            case "GROUP":
                filename = "group_chat.txt";
                break;
            case "COMPANION":
                filename = "ai_companion.txt";
                break;
            case "AI":
                filename = "ai_assistant.txt";
                break;
            default: // PRIVATE
                String[] users = {currentUser, target};
                Arrays.sort(users);
                filename = users[0] + "_" + users[1] + ".txt";
                break;
        }
        return CHAT_HISTORY_DIR.resolve(filename);
    }

    /**
     * 将时间戳字符串转为可读格式
     */
    private String formatTime(String timeStr) {
        try {
            long ts = Long.parseLong(timeStr);
            return FILE_SDF.format(new Date(ts));
        } catch (NumberFormatException e) {
            return timeStr;
        }
    }

    /**
     * 转义内容中的特殊字符，保证每行一条记录
     */
    private String escapeContent(String content) {
        return content.replace("\n", "\\n").replace("\t", "\\t");
    }

    /**
     * 还原转义过的内容
     */
    private String unescapeContent(String content) {
        return content.replace("\\n", "\n").replace("\\t", "\t");
    }
}
