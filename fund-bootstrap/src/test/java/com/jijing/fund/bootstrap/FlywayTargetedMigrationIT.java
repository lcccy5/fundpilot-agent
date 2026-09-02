package com.jijing.fund.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_INTEGRATION_TESTS", matches="true")
class FlywayTargetedMigrationIT {
    @Test void migratesV1ToV8ThenV9ToV14ThenV15ToV18ThenV19ToV23() throws Exception {
        String admin=envUrl().replace("/jijing_agent_test","/");
        String user=System.getenv().getOrDefault("MYSQL_USERNAME","root");
        String password=System.getenv().getOrDefault("MYSQL_PASSWORD","test");
        String db="jijing_flyway_gate";
        try(Connection c=DriverManager.getConnection(admin,user,password)){
            c.createStatement().execute("DROP DATABASE IF EXISTS "+db);
            c.createStatement().execute("CREATE DATABASE "+db+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        String url=envUrl().replace("jijing_agent_test",db);
        migrate(url,user,password,"8");
        Set<String> after8=tables(url,user,password);
        assertFalse(after8.contains("user_account"));
        migrate(url,user,password,"14");
        Set<String> after14=tables(url,user,password);
        assertTrue(after14.contains("user_account"));
        assertTrue(after14.contains("user_portfolio"));
        assertFalse(after14.contains("agent_plan"));
        migrate(url,user,password,"18");
        Set<String> after18=tables(url,user,password);
        assertTrue(after18.contains("agent_plan"));
        assertTrue(after18.contains("agent_approval"));
        assertFalse(after18.contains("outbox_event"));
        migrate(url,user,password,"23");
        Set<String> after23=tables(url,user,password);
        assertTrue(after23.contains("outbox_event"));
        assertTrue(after23.contains("report_job"));
        assertTrue(after23.contains("mcp_connection"));
        try(Connection c=DriverManager.getConnection(admin,user,password)){
            c.createStatement().execute("DROP DATABASE IF EXISTS "+db);
        }
    }

    private static void migrate(String url,String user,String password,String target){
        Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target(target).load().migrate();
    }

    private static Set<String> tables(String url,String user,String password) throws Exception {
        try(Connection c=DriverManager.getConnection(url,user,password); var ps=c.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()"); ResultSet rs=ps.executeQuery()){
            Set<String> names=new java.util.HashSet<>();
            while(rs.next())names.add(rs.getString(1).toLowerCase(java.util.Locale.ROOT));
            return names;
        }
    }

    private static String envUrl(){
        return System.getenv().getOrDefault("MYSQL_TEST_URL","jdbc:mysql://127.0.0.1:3307/jijing_agent_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
    }
}
