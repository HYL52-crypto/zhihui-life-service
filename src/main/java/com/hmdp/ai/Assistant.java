package com.hmdp.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/**
 * AI 智能客服接口。
 * <p>
 * 只需要写接口，不用写实现类：langchain4j 的 AiServices 会在运行时用动态代理生成实现，
 * 代理里自动完成「拼系统提示词 → 取历史记忆 → 调大模型 → 处理工具调用 → 回写记忆」这一整套。
 * <p>
 * @MemoryId：会话隔离的关键。同样的接口、同样的模型，靠 memoryId 区分不同用户的记忆。
 *            如果方法上没有这个参数，langchain4j 会用固定的 "default" 作为 memoryId，
 *            结果是所有用户共用一份记忆——这是最常见的踩坑点。
 */
public interface Assistant {

    /**
     * 多轮对话
     *
     * @param sessionId   会话id，用来区分不同用户的记忆
     * @param userMessage 用户这一轮说的话
     */
    String chat(@MemoryId String sessionId, @UserMessage String userMessage);
}
