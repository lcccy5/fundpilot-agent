package com.jijing.fund.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 进程入口。扫描全部基金模块，并打开调度和 MyBatis 映射。
 */
@SpringBootApplication(scanBasePackages = "com.jijing.fund")
@EnableScheduling
@MapperScan("com.jijing.fund.infrastructure.persistence.mapper")
public class JijingAgentApplication {
    /**
     * 启动 Spring 容器。失败原因由框架日志输出，这里不额外翻译退出码。
     */
    public static void main(String[] args) {
        SpringApplication.run(JijingAgentApplication.class, args);
    }
}
