package com.jijing.fund.infrastructure.lock;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(prefix="fund.sync", name="distributed-lock-enabled", havingValue="true")
public class RedissonConfiguration {
    @Bean(destroyMethod="shutdown") RedissonClient redissonClient(@Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        Config config = new Config(); config.useSingleServer().setAddress("redis://" + host + ":" + port); return Redisson.create(config);
    }
}

