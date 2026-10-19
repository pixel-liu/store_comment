-- Existing databases only: first inspect the result below. If duplicates exist,
-- resolve them according to business records before adding the unique index.
-- No records are automatically deleted. Run once, before deploying the new app.
SELECT user_id, voucher_id, COUNT(*) AS duplicate_count
FROM tb_voucher_order
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;

ALTER TABLE tb_voucher_order
  ADD UNIQUE KEY uk_voucher_order_user_voucher (user_id, voucher_id);

CREATE TABLE IF NOT EXISTS tb_voucher_order_failure (
  order_id bigint(20) NOT NULL,
  user_id bigint(20) UNSIGNED NOT NULL,
  voucher_id bigint(20) UNSIGNED NOT NULL,
  create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
