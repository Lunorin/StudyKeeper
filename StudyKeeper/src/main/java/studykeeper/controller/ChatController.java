package studykeeper.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import studykeeper.common.Result;
import studykeeper.dto.ChatMessageDTO;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.service.ChatService;

import java.util.List;

/**
 * 聊天记录接口（只读）。
 * 目前只有一个：GET /api/chat/history（拉历史消息）。
 * 发消息在 POST /api/ai/chat（AiController），那条路径负责落库；本接口只查不写。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 聊天历史：该用户最近 limit 条消息，按时间正序（最早的在前）。
     * limit 默认 50、最大 200，越界返回 400；一条消息都没有时 data 是空数组。
     */
    @GetMapping("/history")
    public Result<List<ChatMessageDTO>> history(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                                @RequestParam(required = false, defaultValue = "50") Integer limit) {
        try {
            return Result.success(chatService.history(userId, limit));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

}
