SET NAMES utf8mb4;

INSERT INTO tb_user (id, phone, nick_name) VALUES
    (9001, '19900009001', '演示管理员'),
    (9002, '19900009002', '演示窗口人员'),
    (9003, '19900009003', '演示学生');

INSERT INTO tb_shop_type (id, name, sort) VALUES
    (1, '食堂窗口', 1);

INSERT INTO tb_shop (id, name, type_id, images, address, x, y, sold, comments, score) VALUES
    (9001, '梧桐食堂 1 号窗口（演示）', 1, '', '梧桐食堂一楼东侧', 0, 0, 0, 0, 0);

INSERT INTO tb_shop_staff (shop_id, user_id) VALUES (9001, 9002);
