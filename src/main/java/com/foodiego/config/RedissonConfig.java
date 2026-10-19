package com.foodiego.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import javax.annotation.PreDestroy;

@Configuration
public class RedissonConfig {

    private RedissonClient redissonClient;

    @Bean
    public RedissonClient redissonClient(RedisProperties properties){
        // 配置类
        Config config = new Config();
        org.redisson.config.SingleServerConfig server = config.useSingleServer()
                .setAddress((properties.isSsl() ? "rediss://" : "redis://") +
                        properties.getHost() + ":" + properties.getPort())
                .setDatabase(properties.getDatabase());
        if (properties.getPassword() != null && !properties.getPassword().isEmpty()) {
            server.setPassword(properties.getPassword());
        }

        // 创建RedissonClient对象
        redissonClient = Redisson.create(config);
        return redissonClient;
    }

    @PreDestroy
    public void shutdown() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }
}
