ALTER TABLE tb_meal_batch
    ADD COLUMN initial_stock INT NULL,
    ADD COLUMN booking_closed_at DATETIME NULL,
    ADD COLUMN close_applied TINYINT NOT NULL DEFAULT 0,
    ADD KEY idx_meal_batch_close (close_applied, booking_closed_at),
    ADD KEY idx_meal_batch_pickup_end (pickup_end_time, voucher_id);

-- Run with the application stopped. Cancelled orders have already returned their stock.
UPDATE tb_meal_batch b
JOIN tb_seckill_voucher a ON a.voucher_id = b.voucher_id
LEFT JOIN (
    SELECT o.voucher_id, COUNT(*) AS used_stock
    FROM tb_voucher_order o JOIN tb_meal_pickup p ON p.order_id = o.id
    WHERE o.status IN (3, 7, 8)
    GROUP BY o.voucher_id
) booked ON booked.voucher_id = b.voucher_id
SET b.initial_stock = a.stock + COALESCE(booked.used_stock, 0);

ALTER TABLE tb_meal_batch MODIFY COLUMN initial_stock INT NOT NULL;

ALTER TABLE tb_voucher_order ADD KEY idx_order_batch_status (voucher_id, status, id);
