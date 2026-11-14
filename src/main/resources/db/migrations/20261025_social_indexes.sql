-- Run once against existing databases. Resolve duplicate follow rows before applying.
SELECT user_id, follow_user_id, COUNT(*) AS duplicate_count
FROM tb_follow
GROUP BY user_id, follow_user_id
HAVING COUNT(*) > 1;

ALTER TABLE tb_follow
  ADD UNIQUE KEY uk_follow_user_target (user_id, follow_user_id),
  ADD KEY idx_follow_target (follow_user_id, user_id);

ALTER TABLE tb_blog
  ADD KEY idx_blog_hot (liked, id),
  ADD KEY idx_blog_user (user_id, id);

ALTER TABLE tb_blog_comments
  ADD KEY idx_blog_comments_blog_parent (blog_id, parent_id);

ALTER TABLE tb_voucher
  ADD KEY idx_voucher_shop (shop_id, status, type);

CREATE TABLE IF NOT EXISTS tb_blog_like (
  blog_id bigint(20) UNSIGNED NOT NULL,
  user_id bigint(20) UNSIGNED NOT NULL,
  create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (blog_id, user_id),
  KEY idx_blog_like_user (user_id, blog_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
