package org.example.model;

/**
 * 用户数据模型
 */
public class User {

    private long id;
    private String username;
    private String password;   // 加密后的哈希值
    private String salt;       // 盐值
    private String nickname;
    private String avatar;
    private String email;

    public User() {}

    public User(String username, String password, String salt, String nickname) {
        this.username = username;
        this.password = password;
        this.salt = salt;
        this.nickname = nickname;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}