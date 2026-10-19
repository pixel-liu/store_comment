package com.foodiego.service.impl;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.foodiego.dto.VoucherOrderDTO;
import com.foodiego.mapper.VoucherOrderMapper;
import com.foodiego.service.IVoucherOrderService;
import com.foodiego.utils.RedisIdWorker;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real JDBC transactions, constraints and row locks without external services. */
@SpringJUnitConfig(OrderTransactionTest.Config.class)
class OrderTransactionTest {
    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = VoucherOrderMapper.class)
    @Import({VoucherOrderMQServiceImpl.class, SeckillVoucherServiceImpl.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:orders;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean StringRedisTemplate stringRedisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean RabbitTemplate rabbitTemplate() { return mock(RabbitTemplate.class); }
        @Bean RedisIdWorker redisIdWorker() { return mock(RedisIdWorker.class); }
    }

    @Autowired IVoucherOrderService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc.execute("DROP TABLE IF EXISTS tb_voucher_order");
        jdbc.execute("DROP TABLE IF EXISTS tb_seckill_voucher");
        jdbc.execute("DROP TABLE IF EXISTS tb_voucher_order_failure");
        jdbc.execute("CREATE TABLE tb_seckill_voucher(voucher_id BIGINT PRIMARY KEY, stock INT, " +
                "create_time TIMESTAMP, begin_time TIMESTAMP, end_time TIMESTAMP, update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE tb_voucher_order(id BIGINT PRIMARY KEY CHECK(id > 0), user_id BIGINT NOT NULL," +
                "voucher_id BIGINT NOT NULL, pay_type INT DEFAULT 1, status INT DEFAULT 1, " +
                "create_time TIMESTAMP, pay_time TIMESTAMP, use_time TIMESTAMP, refund_time TIMESTAMP," +
                "update_time TIMESTAMP, UNIQUE(user_id, voucher_id))");
        jdbc.execute("CREATE TABLE tb_voucher_order_failure(order_id BIGINT PRIMARY KEY, user_id BIGINT, voucher_id BIGINT)");
        jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock) VALUES(100,10)");
    }

    VoucherOrderDTO order(long id, long user) {
        VoucherOrderDTO dto = new VoucherOrderDTO();
        dto.setId(id); dto.setVoucherId(100L); dto.setUserId(user);
        return dto;
    }
    int stock() { return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=100", Integer.class); }
    int orders() { return jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class); }

    @Test void redeliveryAndDuplicateBusinessRequestDeductOnlyOnce() {
        service.handleVoucherOrder(order(1,1));
        service.handleVoucherOrder(order(1,1));
        service.handleVoucherOrder(order(2,1));
        assertEquals(1, orders()); assertEquals(9, stock());
    }

    @Test void insertionFailureRollsBackStock() {
        assertThrows(RuntimeException.class, () -> service.handleVoucherOrder(order(-1,1)));
        assertEquals(0, orders()); assertEquals(10, stock());
    }

    @Test void noStockThrowsInsteadOfAcknowledgingSuccess() {
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0");
        assertThrows(IllegalStateException.class, () -> service.handleVoucherOrder(order(1,1)));
        assertEquals(0, orders()); assertEquals(0, stock());
    }

    @Test void concurrentDuplicateDeliveriesCommitExactlyOnce() throws Exception {
        runConcurrently(12, i -> service.handleVoucherOrder(order(i+1, 1)));
        assertEquals(1, orders()); assertEquals(9, stock());
    }

    @Test void concurrentDistinctUsersCannotOversell() throws Exception {
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        runConcurrently(20, i -> {
            try { service.handleVoucherOrder(order(i+1, i+1)); accepted.incrementAndGet(); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("库存不足")); }
        });
        assertEquals(10, accepted.get()); assertEquals(10, orders()); assertEquals(0, stock());
    }

    @Test void terminalFailurePreventsLateDeliveryAndDoesNotCompensateSuccess() {
        assertTrue(service.markOrderFailed(order(1,1)));
        assertTrue(service.markOrderFailed(order(1,1)));
        service.handleVoucherOrder(order(1,1));
        assertEquals(0, orders()); assertEquals(10, stock());
        service.handleVoucherOrder(order(2,1));
        assertFalse(service.markOrderFailed(order(2,1)));
        assertFalse(service.markOrderFailed(order(3,1)));
        assertEquals(1, orders()); assertEquals(9, stock());
    }

    @Test void orderIdCollisionDoesNotDeductAnotherStock() {
        service.handleVoucherOrder(order(1,1));
        assertThrows(IllegalStateException.class, () -> service.handleVoucherOrder(order(1,2)));
        assertEquals(1, orders()); assertEquals(9, stock());
    }

    private void runConcurrently(int count, java.util.function.IntConsumer action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i=0; i<count; i++) {
                final int index = i;
                futures.add(executor.submit(() -> { start.await(); action.accept(index); return null; }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }
}
