PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`            INTEGER         NOT NULL PRIMARY KEY AUTOINCREMENT,
    `sender_id`     INTEGER         NOT NULL,
    `receiver_id`   INTEGER         NOT NULL,
    `chat_type`     INTEGER         NOT NULL,
    `message_type`  INTEGER         NOT NULL DEFAULT 0,
    `content`       TEXT            NOT NULL,
    `sent_at`       TEXT            NOT NULL DEFAULT (datetime('now')),
    `is_read`       INTEGER         NOT NULL DEFAULT 0,
    `status`        INTEGER         NOT NULL DEFAULT 0,

    FOREIGN KEY (`sender_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS `idx_session_time` ON `chat_message` (`chat_type`, `receiver_id`, `sent_at`);
CREATE INDEX IF NOT EXISTS `idx_sender_id` ON `chat_message` (`sender_id`);
CREATE INDEX IF NOT EXISTS `idx_sender_receiver_time` ON `chat_message` (`sender_id`, `receiver_id`, `sent_at`);