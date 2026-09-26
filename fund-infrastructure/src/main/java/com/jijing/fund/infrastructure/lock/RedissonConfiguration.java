package com.jijing.fund.infrastructure.lock;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 分布式锁打开时创建单机 Redisson 客户端。地址来自 Redis 主机和端口，默认本机 6379。
 * 这里不设置命令超时。连接失败发生在第一次用锁时，而不是 Bean 方法返回之前。
 */
@Configuration
@ConditionalOnProperty(prefix = "fund.sync", name = "distributed-lock-enabled", havingValue = "true")
public class RedissonConfiguration {
    /**
     * 容器关闭时调用 Redisson 的 shutdown。只配置单节点地址，没有密码或数据库号。
     */
    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(@Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }
}
