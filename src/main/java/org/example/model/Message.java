package org.example.model;

/**
 * 消息数据模型
 */
public class Message {

    private long id;
    private String sender;
    private String receiver;
    private int chatType;      // 0-私聊，1-群聊，2-AI聊天
    private int messageType;   // 0-文本，1-图片，2-文件
    private String content;
    private String time;       // 时间戳字符串
    private boolean isRead;

    public Message() {}

    public Message(String sender, String receiver, String content, String time) {
        this.sender = sender;
        this.receiver = receiver;
        this.content = content;
        this.time = time;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getReceiver() { return receiver; }
    public void setReceiver(String receiver) { this.receiver = receiver; }

    public int getChatType() { return chatType; }
    public void setChatType(int chatType) { this.chatType = chatType; }

    public int getMessageType() { return messageType; }
    public void setMessageType(int messageType) { this.messageType = messageType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }
}