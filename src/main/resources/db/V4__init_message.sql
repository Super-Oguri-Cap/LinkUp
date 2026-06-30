-- =============================================
-- LinkUp 即时通讯系统 - 消息存储表
-- 版本: V1.3
-- 说明: 统一存储私聊和群聊消息，通过 receiver_type 区分
--       采用"大宽表"设计，避免多表 JOIN 影响查询性能
-- =============================================

USE linkup;

CREATE TABLE IF NOT EXISTS `chat_message` (
    -- 消息ID，主键自增
    -- 使用 BIGINT 以支持海量消息（IM 场景消息量极大）
    `id`            BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '消息ID，主键自增',

    -- 发送者ID，外键关联 user 表
    `sender_id`     BIGINT          NOT NULL                 COMMENT '发送者用户ID',

    -- 接收者ID：私聊时为对方用户ID，群聊时为群ID
    `receiver_id`   BIGINT          NOT NULL                 COMMENT '接收者ID（私聊=用户ID，群聊=群ID）',

    -- 会话类型：0-私聊，1-群聊
    -- 用于区分 receiver_id 的含义，也是查询过滤的关键字段
    `chat_type`     TINYINT         NOT NULL                 COMMENT '会话类型：0-私聊，1-群聊',

    -- 消息类型：0-文本，1-图片，2-文件，3-语音，4-视频，5-系统消息
    `message_type`  TINYINT         NOT NULL DEFAULT 0       COMMENT '消息类型：0-文本，1-图片，2-文件，3-语音，4-视频，5-系统消息',

    -- 消息内容
    -- 文本消息直接存文本，图片/文件消息存 JSON 格式的元数据（URL、大小、文件名等）
    `content`       TEXT            NOT NULL                 COMMENT '消息内容（文本或JSON元数据）',

    -- 发送时间，精确到毫秒（高并发下可能有同一秒的多条消息，需要毫秒区分顺序）
    `sent_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '消息发送时间',

    -- 是否已读（仅私聊适用，群聊消息的已读状态在 group_message_read 表单独管理）
    -- 0-未读，1-已读
    `is_read`       TINYINT         NOT NULL DEFAULT 0       COMMENT '是否已读：0-未读，1-已读（仅私聊语义有效）',

    -- 状态：0-正常，1-已撤回，2-已删除
    `status`        TINYINT         NOT NULL DEFAULT 0       COMMENT '消息状态：0-正常，1-已撤回，2-已删除（软删除）',

    -- 主键
    PRIMARY KEY (`id`),

    -- 核心查询索引：按会话查询消息列表（私聊/群聊的聊天记录）
    -- 联合索引覆盖了 chat_type + receiver_id + sent_at，支持高效分页拉取
    KEY `idx_session_time` (`chat_type`, `receiver_id`, `sent_at`),

    -- 发送者索引：查询某用户发送的所有消息
    KEY `idx_sender_id` (`sender_id`),

    -- 发送者+接收者+时间索引：查询私聊双方的消息记录
    KEY `idx_sender_receiver_time` (`sender_id`, `receiver_id`, `sent_at`),

    -- 外键：发送者关联 user 表
    -- ON DELETE RESTRICT：用户注销后消息仍需保留（聊天记录审计）
    CONSTRAINT `fk_msg_sender` FOREIGN KEY (`sender_id`)
        REFERENCES `user` (`id`) ON DELETE RESTRICT

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='LinkUp 消息存储表（私聊+群聊统一存储）';

-- =============================================
-- 索引策略说明
-- =============================================
-- 1. idx_session_time (chat_type, receiver_id, sent_at) — 核心索引
--    - 查询某会话的消息列表（最频操作）：
--      SELECT * FROM chat_message
--      WHERE chat_type = 0 AND receiver_id = ?
--      ORDER BY sent_at DESC LIMIT 20;
--      → 完全命中联合索引，覆盖 WHERE + ORDER BY，无需额外排序
--
-- 2. idx_sender_receiver_time (sender_id, receiver_id, sent_at)
--    - 查询私聊双方的消息记录：
--      SELECT * FROM chat_message
--      WHERE sender_id = ? AND receiver_id = ?
--      ORDER BY sent_at DESC;
--
-- 3. idx_sender_id (sender_id)
--    - 查询某用户发送的所有消息（管理后台审计用）
--
-- 4. 为什么不在 content 字段上建索引？
--    - TEXT 类型只能建前缀索引，对中文无效
--    - 消息搜索应使用 ElasticSearch 等全文搜索引擎，不在 MySQL 做
--
-- 5. 分库分表建议（未来扩展）：
--    - 当单表消息量超过 5000 万条时，建议按 receiver_id 取模分表
--    - 或按 chat_type 拆分为 private_message + group_message 两张表
--    - 历史消息可归档到冷存储（如按月份归档）