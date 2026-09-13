package org.javaup.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @program: hmdp-plus
 * @description: 智能商户推荐客服配置项（新增功能，独立配置，不影响原有业务）
 * @author: hmdp-plus
 **/
@Data
@Component
@ConfigurationProperties(prefix = "assistant")
public class AssistantProperties {

    /**
     * 是否启用大模型增强回复；关闭后走纯规则引擎，功能依旧可用
     */
    private boolean enabled = true;

    /**
     * Ollama 服务地址（开源大模型运行时，可部署在虚拟机中）
     */
    private String baseUrl = "http://127.0.0.1:11434";

    /**
     * 使用的开源模型名称（ollama pull 对应的模型）
     */
    private String model = "qwen2.5:1.5b";

    /**
     * 调用大模型的读超时时间（秒）
     */
    private int timeoutSeconds = 60;

    /**
     * 一次回复中最多推荐的商户数量
     */
    private int maxShops = 5;
}
