package org.example.client;

import org.example.util.MessageProtocol;
import com.google.gson.JsonObject;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * 注册界面 - 支持用户名、密码、昵称注册
 * 校验用户名唯一性，密码使用 SHA-256 加密存储（服务端处理）
 */
public class RegisterFrame extends JFrame {

    private final LinkUpClient client;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JPasswordField confirmPasswordField;
    private JTextField nicknameField;
    private JButton registerButton;
    private javax.swing.Timer registerTimeoutTimer; // 注册超时计时器

    public RegisterFrame(LinkUpClient client) {
        this.client = client;
        initUI();
        // 注册结果回调
        client.setRegisterCallback((success, message) -> SwingUtilities.invokeLater(() -> {
            // 停止超时计时器
            if (registerTimeoutTimer != null && registerTimeoutTimer.isRunning()) {
                registerTimeoutTimer.stop();
            }
            registerButton.setEnabled(true);
            registerButton.setText("注册");
            if (success) {
                JOptionPane.showMessageDialog(this, message, "注册成功", JOptionPane.INFORMATION_MESSAGE);
                dispose();
            } else {
                JOptionPane.showMessageDialog(this, message, "注册失败", JOptionPane.ERROR_MESSAGE);
            }
        }));
    }

    private void initUI() {
        setTitle("LinkUp - 注册");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(400, 400);
        setLocationRelativeTo(null);
        setResizable(false);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 40, 20, 40));
        mainPanel.setBackground(new Color(245, 245, 245));

        // 标题
        JLabel titleLabel = new JLabel("注册新账号", JLabel.CENTER);
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 20));
        titleLabel.setForeground(new Color(0, 120, 212));
        mainPanel.add(titleLabel, BorderLayout.NORTH);

        // 输入面板
        JPanel inputPanel = new JPanel(new GridBagLayout());
        inputPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        Font labelFont = new Font("微软雅黑", Font.PLAIN, 14);
        Font fieldFont = new Font("微软雅黑", Font.PLAIN, 14);

        // 用户名
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        inputPanel.add(createLabel("用户名:", labelFont), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        usernameField = new JTextField(15);
        usernameField.setFont(fieldFont);
        inputPanel.add(usernameField, gbc);

        // 昵称
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        inputPanel.add(createLabel("昵  称:", labelFont), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        nicknameField = new JTextField(15);
        nicknameField.setFont(fieldFont);
        inputPanel.add(nicknameField, gbc);

        // 密码
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        inputPanel.add(createLabel("密  码:", labelFont), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        passwordField = new JPasswordField(15);
        passwordField.setFont(fieldFont);
        inputPanel.add(passwordField, gbc);

        // 确认密码
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
        inputPanel.add(createLabel("确认密码:", labelFont), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        confirmPasswordField = new JPasswordField(15);
        confirmPasswordField.setFont(fieldFont);
        inputPanel.add(confirmPasswordField, gbc);

        mainPanel.add(inputPanel, BorderLayout.CENTER);

        // 按钮
        registerButton = new JButton("注册");
        registerButton.setFont(new Font("微软雅黑", Font.BOLD, 14));
        registerButton.setBackground(new Color(0, 120, 212));
        registerButton.setForeground(Color.WHITE);
        registerButton.setFocusPainted(false);
        registerButton.setPreferredSize(new Dimension(100, 36));
        registerButton.addActionListener(this::onRegister);

        JPanel buttonPanel = new JPanel();
        buttonPanel.setOpaque(false);
        buttonPanel.add(registerButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(mainPanel);
        getRootPane().setDefaultButton(registerButton);
    }

    private JLabel createLabel(String text, Font font) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        return label;
    }

    /**
     * 注册按钮事件：前端校验后发送注册请求
     */
    private void onRegister(ActionEvent e) {
        String username = usernameField.getText().trim();
        String nickname = nicknameField.getText().trim();
        String password = new String(passwordField.getPassword());
        String confirmPassword = new String(confirmPasswordField.getPassword());

        // 前端校验
        if (username.isEmpty() || nickname.isEmpty() || password.isEmpty()) {
            JOptionPane.showMessageDialog(this, "所有字段不能为空", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (username.length() < 3) {
            JOptionPane.showMessageDialog(this, "用户名至少 3 个字符", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (password.length() < 6) {
            JOptionPane.showMessageDialog(this, "密码至少 6 个字符", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!password.equals(confirmPassword)) {
            JOptionPane.showMessageDialog(this, "两次输入的密码不一致", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        registerButton.setEnabled(false);
        registerButton.setText("注册中...");

        // 15秒超时自动恢复按钮（防止服务端无响应导致按钮卡死）
        registerTimeoutTimer = new javax.swing.Timer(15000, evt -> {
            registerButton.setEnabled(true);
            registerButton.setText("注册");
            JOptionPane.showMessageDialog(this, "注册超时，请检查服务器是否正常", "超时", JOptionPane.WARNING_MESSAGE);
            registerTimeoutTimer.stop();
        });
        registerTimeoutTimer.setRepeats(false);
        registerTimeoutTimer.start();

        // 构建注册消息（密码和昵称放入 content 字段的子 JSON）
        JsonObject content = new JsonObject();
        content.addProperty("password", password);
        content.addProperty("nickname", nickname);

        JsonObject registerMsg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_REGISTER, username, "", content.toString());
        client.sendMessage(MessageProtocol.toWire(registerMsg));
    }
}