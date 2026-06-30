package org.example.client.panel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * AI 空间面板
 * 负责 AI 空间 Tab 的 UI 展示和交互，包括 AI 情绪陪伴、群聊摘要、对话润色
 * 通过回调接口与 MainFrame 通信
 */
public class AIPanel extends JPanel {

    /** 润色风格选择下拉框 */
    private final JComboBox<String> polishStyleCombo;
    /** 润色输入框 */
    private final JTextField aiPolishField;
    /** 润色按钮 */
    private final JButton aiPolishButton;

    /** 外部动作监听器 */
    private AIActionListener actionListener;

    /**
     * AI 面板动作监听接口
     */
    public interface AIActionListener {
        /** 打开 AI 伴侣聊天 */
        void onOpenAICompanion();
        /** 生成群聊摘要 */
        void onGenerateGroupSummary();
        /** 对话润色 */
        void onPolish(String text, String style);
        /** 打开 AI 设置 */
        void onOpenAISettings();
    }

    public AIPanel() {
        this.polishStyleCombo = new JComboBox<>(new String[]{"高情商", "专业", "委婉"});
        this.aiPolishField = new JTextField();
        this.aiPolishButton = new JButton("开始润色");

        initUI();
    }

    public void setActionListener(AIActionListener listener) {
        this.actionListener = listener;
    }

    // ==================== UI 初始化 ====================

    private void initUI() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(245, 245, 250));
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // 标题行（含设置按钮）
        add(createTitleRow());
        add(Box.createVerticalStrut(20));

        // 场景 B：AI 情绪陪伴
        add(createAISection("AI 情绪陪伴",
                "有温度、有记忆、懂情绪的 AI 伴侣，感知你的情绪，给你温暖的陪伴",
                "打开 AI 伴侣聊天", e -> {
                    if (actionListener != null) actionListener.onOpenAICompanion();
                }));
        add(Box.createVerticalStrut(15));

        // 场景 A：群聊摘要
        add(createAISection("群聊智能摘要",
                "一键总结错过的 99+ 群聊消息，提取与你相关的待办事项",
                "生成群聊摘要", e -> {
                    if (actionListener != null) actionListener.onGenerateGroupSummary();
                }));
        add(Box.createVerticalStrut(15));

        // 场景 C：对话润色
        add(createPolishSection());

        add(Box.createVerticalGlue());
    }

    /**
     * 标题行：AI 智能空间 + 设置按钮
     */
    private JPanel createTitleRow() {
        JPanel titleRow = new JPanel();
        titleRow.setLayout(new BoxLayout(titleRow, BoxLayout.X_AXIS));
        titleRow.setOpaque(false);
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel aiTitle = new JLabel("AI 智能空间");
        aiTitle.setFont(new Font("微软雅黑", Font.BOLD, 20));
        aiTitle.setForeground(new Color(0, 120, 212));
        titleRow.add(aiTitle);
        titleRow.add(Box.createHorizontalGlue());

        JButton settingsBtn = new JButton("设置");
        settingsBtn.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        settingsBtn.setBackground(new Color(100, 100, 100));
        settingsBtn.setForeground(Color.WHITE);
        settingsBtn.setFocusPainted(false);
        settingsBtn.setToolTipText("配置 API Key、模型、System Prompt 等");
        settingsBtn.addActionListener(e -> {
            if (actionListener != null) actionListener.onOpenAISettings();
        });
        titleRow.add(settingsBtn);

        return titleRow;
    }

    /**
     * 创建 AI 功能卡片
     */
    private JPanel createAISection(String title, String desc, String btnText, ActionListener action) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(Color.WHITE);
        section.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 220)),
                BorderFactory.createEmptyBorder(15, 15, 15, 15)));
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 16));
        titleLabel.setForeground(new Color(0, 120, 212));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(titleLabel);

        JLabel descLabel = new JLabel(desc);
        descLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        descLabel.setForeground(new Color(68, 68, 68));
        descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(descLabel);
        section.add(Box.createVerticalStrut(10));

        JButton btn = new JButton(btnText);
        btn.setFont(new Font("微软雅黑", Font.BOLD, 13));
        btn.setBackground(new Color(0, 120, 212));
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.addActionListener(action);
        section.add(btn);

        return section;
    }

    /**
     * 对话润色区域
     */
    private JPanel createPolishSection() {
        JPanel polishSection = new JPanel();
        polishSection.setLayout(new BoxLayout(polishSection, BoxLayout.Y_AXIS));
        polishSection.setBackground(Color.WHITE);
        polishSection.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 220)),
                BorderFactory.createEmptyBorder(15, 15, 15, 15)));
        polishSection.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        polishSection.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel polishTitle = new JLabel("对话润色");
        polishTitle.setFont(new Font("微软雅黑", Font.BOLD, 16));
        polishTitle.setForeground(new Color(255, 152, 0));
        polishTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        polishSection.add(polishTitle);

        JLabel polishDesc = new JLabel("在发送前让 AI 帮你优化语气，更得体地表达");
        polishDesc.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        polishDesc.setForeground(new Color(68, 68, 68));
        polishDesc.setAlignmentX(Component.LEFT_ALIGNMENT);
        polishSection.add(polishDesc);
        polishSection.add(Box.createVerticalStrut(10));

        // 润色输入框
        aiPolishField.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        aiPolishField.setForeground(Color.BLACK);
        aiPolishField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        aiPolishField.setAlignmentX(Component.LEFT_ALIGNMENT);
        polishSection.add(aiPolishField);
        polishSection.add(Box.createVerticalStrut(8));

        // 润色按钮 + 风格选择
        JPanel polishRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        polishRow.setOpaque(false);
        polishRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        polishStyleCombo.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        polishStyleCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setForeground(isSelected ? Color.WHITE : Color.BLACK);
                return label;
            }
        });
        polishRow.add(new JLabel("风格:") {{ setForeground(new Color(50, 50, 50)); }});
        polishRow.add(polishStyleCombo);

        aiPolishButton.setFont(new Font("微软雅黑", Font.BOLD, 13));
        aiPolishButton.setBackground(new Color(255, 152, 0));
        aiPolishButton.setForeground(Color.WHITE);
        aiPolishButton.setFocusPainted(false);
        aiPolishButton.addActionListener(e -> {
            if (actionListener != null) {
                String text = aiPolishField.getText().trim();
                String style = (String) polishStyleCombo.getSelectedItem();
                actionListener.onPolish(text, style);
            }
        });
        polishRow.add(aiPolishButton);

        polishSection.add(polishRow);
        return polishSection;
    }

    // ==================== 公共方法 ====================

    /**
     * 设置润色输入框内容
     */
    public void setPolishFieldText(String text) {
        aiPolishField.setText(text);
    }

    /**
     * 设置润色输入框启用状态
     */
    public void setPolishFieldEnabled(boolean enabled) {
        aiPolishField.setEnabled(enabled);
    }

    /**
     * 获取润色输入框内容
     */
    public String getPolishFieldText() {
        return aiPolishField.getText();
    }
}
