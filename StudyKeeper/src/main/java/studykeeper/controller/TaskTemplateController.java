package studykeeper.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studykeeper.common.Result;
import studykeeper.dto.TaskTemplateDTO;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.service.TaskTemplateService;

import java.util.List;

/**
 * 长期任务接口。
 */
@RestController
@RequestMapping("/api/template")
public class TaskTemplateController {

    private final TaskTemplateService taskTemplateService;

    public TaskTemplateController(TaskTemplateService taskTemplateService) {
        this.taskTemplateService = taskTemplateService;
    }

    /**
     * 长期任务列表。
     */
    @GetMapping
    public Result<List<TaskTemplateDTO>> list(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        return Result.success(taskTemplateService.list(userId));
    }

    /**
     * 新增长期任务。repeatDays 不合法时返回 400。
     */
    @PostMapping
    public Result<TaskTemplateDTO> create(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                          @RequestBody TaskTemplateDTO request) {
        try {
            return Result.success(taskTemplateService.create(userId, request));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 修改长期任务。repeatDays 不合法时返回 400，任务不存在或不属于当前用户时返回 404。
     */
    @PutMapping("/{id}")
    public Result<TaskTemplateDTO> update(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                          @PathVariable Long id, @RequestBody TaskTemplateDTO request) {
        try {
            TaskTemplateDTO updated = taskTemplateService.update(userId, id, request);
            if (updated == null) {
                return Result.error(404, "长期任务不存在");
            }
            return Result.success(updated);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 删除长期任务。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                               @PathVariable Long id) {
        if (!taskTemplateService.delete(userId, id)) {
            return Result.error(404, "长期任务不存在");
        }
        return Result.success();
    }

}
