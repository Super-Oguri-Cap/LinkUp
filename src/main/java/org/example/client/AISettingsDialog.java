package org.example.client;

import org.example.server.AISettings;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * AI 设置对话框 — 允许用户配置 API Key、模型、System Prompt 等
 * 包含两个 Tab：API 连接配置、System Prompt 自定义
 */
public class AISettingsDialog extends JDialog {

    private final AISettings settings = AISettings.getInstance();

    // ==================== API 配置 Tab 组件 ====================
    private JTextField apiUrlField;
    private JPasswordField apiKeyField;
    private JTextField modelField;

    // ==================== System Prompt Tab 组件 ====================
    private JTextArea chatPromptArea;
    private JTextArea companionPromptArea;
    private JTextArea summaryPromptArea;
    private JTextArea polishPromptArea;

    // ==================== 按钮 ====================
    private JButton saveButton;
    private JButton cancelButton;
    private JButton resetButton;

    public AISettingsDialog(JFrame parent) {
        super(parent, "AI 设置 - 配置 API Key 和 System Prompt", true);
        initUI();
        loadSettings();
        pack();
        setLocationRelativeTo(parent);
        setMinimumSize(new Dimension(700, 550));
    }

    private void initUI() {
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("微软雅黑", Font.BOLD, 14));

        // 添加两个 Tab
        tabbedPane.addTab("API 连接", createAPITab());
        tabbedPane.addTab("System Prompt", createPromptTab());

        // 底部按钮面板
        JPanel buttonPanel = createButtonPanel();

        // 主布局
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    // ==================== API 连接 Tab ====================

    private JPanel createAPITab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 5, 8, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // 说明文字
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        JLabel infoLabel = new JLabel("<html>配置 AI API 连接信息。支持所有兼容 OpenAI 格式的 API 服务。<br>" +
                "例如：通义千问、DeepSeek、ChatGPT 等。</html>");
        infoLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        infoLabel.setForeground(new Color(100, 100, 100));
        panel.add(infoLabel, gbc);

        // API URL
        gbc.gridwidth = 1; gbc.gridy = 1;
        JLabel urlLabel = new JLabel("API 地址:");
        urlLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        panel.add(urlLabel, gbc);

        gbc.gridx = 1;
        apiUrlField = new JTextField(35);
        apiUrlField.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        apiUrlField.setToolTipText("API 服务地址，如 https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        panel.add(apiUrlField, gbc);

        // API Key
        gbc.gridx = 0; gbc.gridy = 2;
        JLabel keyLabel = new JLabel("API Key:");
        keyLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        panel.add(keyLabel, gbc);

        gbc.gridx = 1;
        apiKeyField = new JPasswordField(35);
        apiKeyField.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        apiKeyField.setToolTipText("你的 API Key，如 sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        panel.add(apiKeyField, gbc);

        // 模型
        gbc.gridx = 0; gbc.gridy = 3;
        JLabel modelLabel = new JLabel("模型名称:");
        modelLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        panel.add(modelLabel, gbc);

        gbc.gridx = 1;
        modelField = new JTextField(35);
        modelField.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        modelField.setToolTipText("模型名称，如 qwen-plus、qwen-max、deepseek-chat、gpt-4o 等");
        panel.add(modelField, gbc);

        // 常用 API 快速选择
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
        panel.add(createQuickSelector(), gbc);

        // 填充空白
        gbc.gridy = 5; gbc.weighty = 1.0;
        panel.add(new JLabel(""), gbc);

        return panel;
    }

    /**
     * 常用 API 快速选择面板
     */
    private JPanel createQuickSelector() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        panel.setBorder(new TitledBorder("快速选择 API 服务"));
        panel.setFont(new Font("微软雅黑", Font.PLAIN, 12));

        JButton qwenBtn = new JButton("通义千问");
        qwenBtn.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        qwenBtn.addActionListener(e -> {
            apiUrlField.setText("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
            modelField.setText("qwen-plus");
        });

        JButton deepseekBtn = new JButton("DeepSeek");
        deepseekBtn.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        deepseekBtn.addActionListener(e -> {
            apiUrlField.setText("https://api.deepseek.com/v1/chat/completions");
            modelField.setText("deepseek-chat");
        });

        JButton openaiBtn = new JButton("OpenAI");
        openaiBtn.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        openaiBtn.addActionListener(e -> {
            apiUrlField.setText("https://api.openai.com/v1/chat/completions");
            modelField.setText("gpt-4o");
        });

        JButton lmStudioBtn = new JButton("LM Studio");
        lmStudioBtn.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        lmStudioBtn.addActionListener(e -> {
            apiUrlField.setText("http://localhost:1234/v1/chat/completions");
            modelField.setText("local-model");
        });

        JButton ollamaBtn = new JButton("Ollama");
        ollamaBtn.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        ollamaBtn.addActionListener(e -> {
            apiUrlField.setText("http://localhost:11434/v1/chat/completions");
            modelField.setText("llama3");
        });

        panel.add(qwenBtn);
        panel.add(deepseekBtn);
        panel.add(openaiBtn);
        panel.add(lmStudioBtn);
        panel.add(ollamaBtn);
        return panel;
    }

    // ==================== System Prompt Tab ====================

    private JPanel createPromptTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 普通 AI 聊天 Prompt
        panel.add(createPromptSection("AI 聊天 System Prompt（AI 小助手的角色设定）",
                chatPromptArea = createPromptArea()));
        panel.add(Box.createVerticalStrut(10));

        // 情绪陪伴 Prompt
        panel.add(createPromptSection("情绪陪伴 System Prompt（AI 伴侣「小暖」的角色设定）",
                companionPromptArea = createPromptArea()));
        panel.add(Box.createVerticalStrut(10));

        // 群聊摘要 Prompt
        panel.add(createPromptSection("群聊摘要 System Prompt（注意：%s 会被替换为当前用户名）",
                summaryPromptArea = createPromptArea()));
        panel.add(Box.createVerticalStrut(10));

        // 对话润色 Prompt
        panel.add(createPromptSection("对话润色 System Prompt（注意：%s 会被替换为润色风格）",
                polishPromptArea = createPromptArea()));

        return panel;
    }

    private JPanel createPromptSection(String title, JTextArea textArea) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(new TitledBorder(title));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(600, 120));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JTextArea createPromptArea() {
        JTextArea area = new JTextArea(6, 50);
        area.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        return area;
    }

    // ==================== 按钮面板 ====================

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        resetButton = new JButton("恢复默认");
        resetButton.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        resetButton.addActionListener(e -> resetToDefaults());

        saveButton = new JButton("保存");
        saveButton.setFont(new Font("微软雅黑", Font.BOLD, 13));
        saveButton.setBackground(new Color(0, 120, 212));
        saveButton.setForeground(Color.WHITE);
        saveButton.setFocusPainted(false);
        saveButton.addActionListener(e -> saveSettings());

        cancelButton = new JButton("取消");
        cancelButton.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        cancelButton.addActionListener(e -> dispose());

        panel.add(resetButton);
        panel.add(cancelButton);
        panel.add(saveButton);

        return panel;
    }

    // ==================== 数据操作 ====================

    /**
     * 加载当前配置到 UI 控件
     */
    private void loadSettings() {
        apiUrlField.setText(settings.getApiUrl());
        apiKeyField.setText(settings.getApiKey());
        modelField.setText(settings.getModel());
        chatPromptArea.setText(settings.getChatPrompt());
        companionPromptArea.setText(settings.getCompanionPrompt());
        summaryPromptArea.setText(settings.getSummaryPrompt());
        polishPromptArea.setText(settings.getPolishPrompt());
    }

    /**
     * 保存 UI 控件数据到配置并写入文件
     */
    private void saveSettings() {
        // 验证必填项：只有非本地模型才强制要求 API Key
        String url = apiUrlField.getText().trim();
        boolean isLocalModel = AISettings.isLocalUrl(url);
        if (apiKeyField.getPassword().length == 0 && !isLocalModel) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "API Key 为空，AI 调用将无法正常工作。\n" +
                    "如果使用的是本地模型（Ollama / LM Studio），可以忽略此提示。\n\n" +
                    "是否仍要保存？",
                    "API Key 为空", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }

        settings.setApiUrl(apiUrlField.getText().trim());
        settings.setApiKey(new String(apiKeyField.getPassword()));
        settings.setModel(modelField.getText().trim());
        settings.setChatPrompt(chatPromptArea.getText().trim());
        settings.setCompanionPrompt(companionPromptArea.getText().trim());
        settings.setSummaryPrompt(summaryPromptArea.getText().trim());
        settings.setPolishPrompt(polishPromptArea.getText().trim());

        settings.save();

        JOptionPane.showMessageDialog(this,
                "AI 配置已保存成功！\n生效范围：所有新发送的 AI 请求",
                "保存成功", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    /**
     * 恢复默认配置到 UI 控件
     */
    private void resetToDefaults() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要恢复默认配置吗？\n当前配置将被覆盖。",
                "确认恢复", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            apiUrlField.setText("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
            apiKeyField.setText("");
            modelField.setText("qwen-plus");
            chatPromptArea.setText(settings.getChatPrompt());
            companionPromptArea.setText(settings.getCompanionPrompt());
            summaryPromptArea.setText(settings.getSummaryPrompt());
            polishPromptArea.setText(settings.getPolishPrompt());
        }
    }
}