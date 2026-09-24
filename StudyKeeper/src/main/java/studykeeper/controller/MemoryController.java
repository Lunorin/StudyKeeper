package studykeeper.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studykeeper.common.Result;
import studykeeper.dto.UserMemoryDTO;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.service.MemoryService;

import java.util.List;

/**
 * 用户画像记忆接口（前端「我的画像」页用）：查看 / 新增 / 删除。
 * <p>
 * 只能操作自己的记忆（userId 来自 token）；不做编辑、不做批量删除、不做分类统计；
 * 记忆的自动沉淀仍由「聊天后异步提炼」那条链路负责（见 MemoryService.extractAndSave），本 Controller 不碰 AI。
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    /** 记忆不存在或不属于当前用户（decisions §1.1：两种情况统一 404） */
    private static final String NOT_FOUND_MESSAGE = "记忆不存在";

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 当前用户的全部记忆。
     * 按分类分组，组间顺序 event &gt; goal &gt; emotion &gt; habit &gt; preference（重要的在前），组内按 updatedAt 倒序；
     * 没有记忆时 data 是空数组。
     */
    @GetMapping
    public Result<List<UserMemoryDTO>> list(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        return Result.success(memoryService.list(userId));
    }

    /**
     * 新增一条记忆。请求体只读 category 与 content：
     * category 不是 5 类之一返回 400「无效的分类」；content 为空或超过 500 字返回 400。
     */
    @PostMapping
    public Result<UserMemoryDTO> create(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                        @RequestBody UserMemoryDTO request) {
        try {
            return Result.success(memoryService.create(userId, request));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 删除一条记忆。不存在或不属于当前用户返回 404「记忆不存在」。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                               @PathVariable Long id) {
        if (!memoryService.delete(userId, id)) {
            return Result.error(404, NOT_FOUND_MESSAGE);
        }
        return Result.success();
    }

}
