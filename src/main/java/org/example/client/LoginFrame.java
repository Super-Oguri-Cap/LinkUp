package org.example.client;

import org.example.util.MessageProtocol;
import com.google.gson.JsonObject;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * 登录界面 - 使用 Swing 构建
 * 支持用户名密码登录，登录成功后跳转到主界面
 */
public class LoginFrame extends JFrame {

    private final LinkUpClient client;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JButton loginButton;
    private JButton registerButton;
    private javax.swing.Timer loginTimeoutTimer; // 登录超时计时器

    public LoginFrame(LinkUpClient client) {
        this.client = client;
        initUI();
    }

    private void initUI() {
        setTitle("LinkUp - 登录");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(400, 350);
        setLocationRelativeTo(null); // 窗口居中
        setResizable(false);

        // 主面板：使用 BorderLayout
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(30, 40, 30, 40));
        mainPanel.setBackground(new Color(245, 245, 245));

        // 标题
        JLabel titleLabel = new JLabel("LinkUp 即时通讯", JLabel.CENTER);
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 24));
        titleLabel.setForeground(new Color(0, 120, 212));
        mainPanel.add(titleLabel, BorderLayout.NORTH);

        // 输入面板
        JPanel inputPanel = new JPanel(new GridBagLayout());
        inputPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // 用户名标签
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        JLabel userLabel = new JLabel("用户名:");
        userLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        inputPanel.add(userLabel, gbc);

        // 用户名输入框
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1;
        usernameField = new JTextField(15);
        usernameField.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        inputPanel.add(usernameField, gbc);

        // 密码标签
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        JLabel passLabel = new JLabel("密  码:");
        passLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        inputPanel.add(passLabel, gbc);

        // 密码输入框
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1;
        passwordField = new JPasswordField(15);
        passwordField.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        inputPanel.add(passwordField, gbc);

        mainPanel.add(inputPanel, BorderLayout.CENTER);

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        buttonPanel.setOpaque(false);

        loginButton = new JButton("登录");
        styleButton(loginButton, new Color(0, 120, 212), Color.WHITE);
        loginButton.addActionListener(this::onLogin);

        registerButton = new JButton("注册");
        styleButton(registerButton, new Color(100, 180, 100), Color.WHITE);
        registerButton.addActionListener(this::onRegister);

        buttonPanel.add(loginButton);
        buttonPanel.add(registerButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(mainPanel);

        // 回车键触发登录
        getRootPane().setDefaultButton(loginButton);
    }

    /**
     * 按钮样式设置
     */
    private void styleButton(JButton button, Color bgColor, Color fgColor) {
        button.setFont(new Font("微软雅黑", Font.BOLD, 14));
        button.setBackground(bgColor);
        button.setForeground(fgColor);
        button.setFocusPainted(false);
        button.setPreferredSize(new Dimension(100, 36));
        button.setBorder(BorderFactory.createEmptyBorder(5, 20, 5, 20));
    }

    /**
     * 登录按钮事件：构建登录消息发送到服务端
     */
    private void onLogin(ActionEvent e) {
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (username.isEmpty() || password.isEmpty()) {
            JOptionPane.showMessageDialog(this, "用户名和密码不能为空", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        loginButton.setEnabled(false);
        loginButton.setText("登录中...");

        // 15秒超时自动恢复按钮（防止服务端无响应导致按钮卡死）
        loginTimeoutTimer = new javax.swing.Timer(15000, evt -> {
            loginButton.setEnabled(true);
            loginButton.setText("登录");
            JOptionPane.showMessageDialog(this, "登录超时，请检查服务器是否正常", "超时", JOptionPane.WARNING_MESSAGE);
            loginTimeoutTimer.stop();
        });
        loginTimeoutTimer.setRepeats(false);
        loginTimeoutTimer.start();

        // 设置当前用户名（登录成功前预绑定）
        client.setCurrentUser(username);

        // 缓存密码供断线重连使用
        client.cachePassword(password);

        // 构建登录消息并发送到服务端
        JsonObject loginMsg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_LOGIN, username, "", password);
        client.sendMessage(MessageProtocol.toWire(loginMsg));
    }

    /**
     * 注册按钮事件：打开注册窗口
     */
    private void onRegister(ActionEvent e) {
        RegisterFrame registerFrame = new RegisterFrame(client);
        registerFrame.setVisible(true);
    }

    /**
     * 处理登录结果（由 LinkUpClient 调用）
     */
    public void handleLoginResult(boolean success, String message) {
        // 停止超时计时器
        if (loginTimeoutTimer != null && loginTimeoutTimer.isRunning()) {
            loginTimeoutTimer.stop();
        }
        SwingUtilities.invokeLater(() -> {
            loginButton.setEnabled(true);
            loginButton.setText("登录");

            if (success) {
                JOptionPane.showMessageDialog(this, message, "登录成功", JOptionPane.INFORMATION_MESSAGE);
                // 打开主界面
                MainFrame mainFrame = new MainFrame(client);
                mainFrame.setVisible(true);
                dispose(); // 关闭登录窗口
            } else {
                JOptionPane.showMessageDialog(this, message, "登录失败", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}