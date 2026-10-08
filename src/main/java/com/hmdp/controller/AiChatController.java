package com.hmdp.controller;

import com.hmdp.ai.Assistant;
import com.hmdp.ai.RedisChatMemoryStore;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * AI 智能客服对外接口。
 * <p>
 * 测试方式（无需登录）：
 * curl -X POST "http://localhost:8081/ai/chat?sessionId=test1&message=帮我找一家咖啡店"
 * curl -X POST "http://localhost:8081/ai/chat?sessionId=test1&message=帮我预约id是1的那家，今晚6点"
 * curl -X POST "http://localhost:8081/ai/chat?sessionId=test1&message=我约了什么"
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AiChatController {

    /**
     * required=false：没在 application.yaml 里配 ai.deepseek.api-key 时不会创建这个 Bean，
     * 这里注入 null 而不是让 Spring 启动失败，接口会给一句友好提示。
     */
    @Autowired(required = false)
    private Assistant assistant;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @PostMapping("/chat")
    public Result chat(@RequestParam("message") String message,
                       @RequestParam(value = "sessionId", required = false) String sessionId) {
        if (message == null || message.trim().isEmpty()) {
            return Result.fail("message 不能为空");
        }
        if (assistant == null) {
            return Result.fail("智能客服未启用：请先在 application.yaml 配置 ai.deepseek.api-key");
        }
        String sid = (sessionId == null || sessionId.trim().isEmpty())
                ? resolveSessionId()
                : sessionId.trim();

        long start = System.currentTimeMillis();
        try {
            String answer = assistant.chat(sid, message);
            log.info("[AI客服] sessionId={}, 耗时={}ms", sid, System.currentTimeMillis() - start);
            return Result.ok(answer);
        } catch (Exception e) {
            log.error("[AI客服] 调用失败, sessionId={}", sid, e);
            return Result.fail("智能客服暂时不可用：" + e.getMessage());
        }
    }

    /** 清空某个会话的记忆（前端"新会话"按钮用） */
    @DeleteMapping("/chat/{sessionId}")
    public Result clearMemory(@PathVariable("sessionId") String sessionId) {
        redisChatMemoryStore.deleteMessages(sessionId);
        return Result.ok("会话记忆已清空");
    }

    /** 没传 sessionId 时：登录了就用 userId，没登录就用一个固定串 */
    private String resolveSessionId() {
        try {
            if (UserHolder.getUser() != null) {
                return "u_" + UserHolder.getUser().getId();
            }
        } catch (Exception e) {
            // ignore
        }
        return "guest";
    }
}
