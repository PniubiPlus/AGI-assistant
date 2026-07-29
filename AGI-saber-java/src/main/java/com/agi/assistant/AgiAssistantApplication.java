package com.agi.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        KafkaAutoConfiguration.class
})
public class AgiAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgiAssistantApplication.class, args);
        printBanner();
    }

    private static void printBanner() {
        System.out.println("========================================");
        System.out.println("Final Stage · AGI 智能助手启动成功");
        System.out.println("========================================");
        System.out.println("[Stage 1] LLM Chat      — 智能对话");
        System.out.println("[Stage 2] RAG           — 知识库检索");
        System.out.println("[Stage 3] Tool Agent    — 工具调用");
        System.out.println("[Stage 4] ReAct         — 多步推理");
        System.out.println("[Stage 5] Memory        — 三层记忆");
        System.out.println("[Stage 6] Harness       — 稳定执行");
        System.out.println("----------------------------------------");
        System.out.println("[READY] 道阻且长，行则将至。");
        System.out.println("========================================");
    }
}
