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
import studykeeper.dto.TaskBatchRequest;
import studykeeper.dto.TaskBatchResult;
import studykeeper.dto.TaskStatusDTO;
import studykeeper.dto.TodayTasksDTO;
import studykeeper.entity.Task;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.service.TaskService;

/**
 * 任务接口。
 */
@RestController
@RequestMapping("/api/task")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 创建任务，返回统一包装结构 Result&lt;Task&gt;。
     */
    @PostMapping
    public Result<Task> create(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                               @RequestBody Task task) {
        Task created = taskService.create(userId, task);
        return Result.success(created);
    }

    /**
     * 今日任务列表 + 完成度统计。
     */
    @GetMapping("/today")
    public Result<TodayTasksDTO> today(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        return Result.success(taskService.todayTasks(userId));
    }

    /**
     * 修改任务，只允许改 title / description / startTime / endTime / priority。
     */
    @PutMapping("/{id}")
    public Result<Task> update(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                               @PathVariable Long id, @RequestBody Task task) {
        Task updated = taskService.update(userId, id, task);
        if (updated == null) {
            return Result.error(404, "任务不存在");
        }
        return Result.success(updated);
    }

    /**
     * 删除任务。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                               @PathVariable Long id) {
        if (!taskService.delete(userId, id)) {
            return Result.error(404, "任务不存在");
        }
        return Result.success();
    }

    /**
     * 标记任务完成。
     */
    @PostMapping("/{id}/done")
    public Result<TaskStatusDTO> done(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                      @PathVariable Long id) {
        TaskStatusDTO result = taskService.done(userId, id);
        if (result == null) {
            return Result.error(404, "任务不存在");
        }
        return Result.success(result);
    }

    /**
     * 取消任务完成。
     */
    @PostMapping("/{id}/undo")
    public Result<TaskStatusDTO> undo(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                      @PathVariable Long id) {
        TaskStatusDTO result = taskService.undo(userId, id);
        if (result == null) {
            return Result.error(404, "任务不存在");
        }
        return Result.success(result);
    }

    /**
     * 批量创建 AI 拆解出来的任务：一次提交多条，全部成功或全部失败（同一个事务）。
     * planDate 为空、tasks 为空、条目的 title 为空或时间不是 HH:mm 时返回 400。
     */
    @PostMapping("/batch")
    public Result<TaskBatchResult> batch(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                         @RequestBody TaskBatchRequest request) {
        try {
            return Result.success(taskService.createBatch(userId, request));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

}
