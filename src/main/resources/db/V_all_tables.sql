USE linkup;
CREATE TABLE IF NOT EXISTS `user` (

    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `username`      VARCHAR(64)     NOT NULL,
    `password`      VARCHAR(128)    NOT NULL,
    `salt`          VARCHAR(32)     NOT NULL,
    `nickname`      VARCHAR(64)     NOT NULL,
    `avatar`        VARCHAR(255)    DEFAULT NULL,
    `email`         VARCHAR(128)    DEFAULT NULL,
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `last_login_at` DATETIME(3)     DEFAULT NULL,
    `online_status` TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_email` (`email`),
    KEY `idx_online_last_login` (`online_status`, `last_login_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `friendship` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `user_a_id`     BIGINT          NOT NULL,
    `user_b_id`     BIGINT          NOT NULL,
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `remark`        VARCHAR(64)     DEFAULT NULL,
    `remark_owner`  TINYINT         NOT NULL DEFAULT 0,
    `is_blocked`    TINYINT         NOT NULL DEFAULT 0,
    `blocked_by`    TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_pair` (`user_a_id`, `user_b_id`),
    CONSTRAINT `fk_friendship_user_a` FOREIGN KEY (`user_a_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_friendship_user_b` FOREIGN KEY (`user_b_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `group_info` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(128)    NOT NULL,
    `avatar`        VARCHAR(255)    DEFAULT NULL,
    `owner_id`      BIGINT          NOT NULL,
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `announcement`  TEXT            DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_owner_id` (`owner_id`),
    CONSTRAINT `fk_group_owner` FOREIGN KEY (`owner_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `group_member` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `group_id`      BIGINT          NOT NULL,
    `user_id`       BIGINT          NOT NULL,
    `joined_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `role`          TINYINT         NOT NULL DEFAULT 0,
    `group_nickname` VARCHAR(64)    DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_user` (`group_id`, `user_id`),
    KEY `idx_user_id` (`user_id`),
    CONSTRAINT `fk_gm_group` FOREIGN KEY (`group_id`) REFERENCES `group_info` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_gm_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `sender_id`     BIGINT          NOT NULL,
    `receiver_id`   BIGINT          NOT NULL,
    `chat_type`     TINYINT         NOT NULL,
    `message_type`  TINYINT         NOT NULL DEFAULT 0,
    `content`       TEXT            NOT NULL,
    `sent_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `is_read`       TINYINT         NOT NULL DEFAULT 0,
    `status`        TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_session_time` (`chat_type`, `receiver_id`, `sent_at`),
    KEY `idx_sender_id` (`sender_id`),
    KEY `idx_sender_receiver_time` (`sender_id`, `receiver_id`, `sent_at`),
    CONSTRAINT `fk_msg_sender` FOREIGN KEY (`sender_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SELECT '所有表创建成功' AS result;