package com.jijing.fund.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.jijing.fund")
@EnableScheduling
@MapperScan("com.jijing.fund.infrastructure.persistence.mapper")
public class JijingAgentApplication {
    public static void main(String[] args) { SpringApplication.run(JijingAgentApplication.class, args); }
}
