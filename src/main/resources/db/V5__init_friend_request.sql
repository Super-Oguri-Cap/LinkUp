PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS `friend_request` (
    `id`            INTEGER         NOT NULL PRIMARY KEY AUTOINCREMENT,
    `from_user_id`  INTEGER         NOT NULL,
    `to_user_id`    INTEGER         NOT NULL,
    `status`        INTEGER         NOT NULL DEFAULT 0,
    `created_at`    TEXT            NOT NULL DEFAULT (datetime('now')),
    `updated_at`    TEXT            NOT NULL DEFAULT (datetime('now')),

    FOREIGN KEY (`from_user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    FOREIGN KEY (`to_user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS `idx_from_user` ON `friend_request` (`from_user_id`);
CREATE INDEX IF NOT EXISTS `idx_to_user` ON `friend_request` (`to_user_id`);
CREATE INDEX IF NOT EXISTS `idx_to_user_status` ON `friend_request` (`to_user_id`, `status`);