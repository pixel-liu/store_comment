CREATE TABLE IF NOT EXISTS tb_shop_staff (
    shop_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (shop_id, user_id),
    KEY idx_shop_staff_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_meal_batch (
    voucher_id BIGINT UNSIGNED NOT NULL,
    pickup_begin_time DATETIME NOT NULL,
    pickup_end_time DATETIME NOT NULL,
    published TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (voucher_id),
    KEY idx_meal_batch_published (published, voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_meal_pickup (
    order_id BIGINT NOT NULL,
    pickup_code CHAR(8) NOT NULL,
    redeemed_by BIGINT UNSIGNED NULL,
    PRIMARY KEY (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_meal_restock (
    order_id BIGINT NOT NULL,
    voucher_id BIGINT UNSIGNED NOT NULL,
    processed TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (order_id),
    KEY idx_meal_restock_processed (processed, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
