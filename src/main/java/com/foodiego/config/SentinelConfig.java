package com.foodiego.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 配置：注册 @SentinelResource 切面 + 初始化流控规则
 */
@Configuration
public class SentinelConfig {

    @Value("${foodiego.sentinel-log-dir:./logs/sentinel}")
    private String sentinelLogDir;

    /**
     * 开启 @SentinelResource 注解支持
     */
    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    /**
     * 初始化流控规则
     */
    @PostConstruct
    public void initFlowRules() {
        // Sentinel defaults to C:\\logs\\csp on Windows, which is not writable for
        // ordinary users. Configure its log directory before the manager class loads.
        if (System.getProperty("csp.sentinel.log.dir") == null) {
            try {
                Path logDir = Path.of(sentinelLogDir).toAbsolutePath().normalize();
                Files.createDirectories(logDir);
                System.setProperty("csp.sentinel.log.dir", logDir.toString());
            } catch (Exception e) {
                throw new IllegalStateException("无法创建 Sentinel 日志目录", e);
            }
        }
        List<FlowRule> rules = new ArrayList<>();

        // 查询店铺接口 — 单机 QPS 上限 50
        rules.add(new FlowRule("queryShopById")
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(50));

        // 按类型查询店铺 — 单机 QPS 上限 30（涉及 Redis GEO + DB 批量查）
        rules.add(new FlowRule("queryShopByType")
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(30));

        // 秒杀下单接口 — 单机 QPS 上限 2000（Tomcat 200线程 + Redis Lua ~1ms，实际压测后调优）
        rules.add(new FlowRule("seckillVoucher")
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(2000));

        FlowRuleManager.loadRules(rules);
    }
}
