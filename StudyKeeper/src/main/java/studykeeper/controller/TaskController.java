package studykeeper.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import studykeeper.entity.Task;
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
     * 创建任务，直接返回落库后的 Task 对象。
     */
    @PostMapping
    public Task create(@RequestBody Task task) {
        return taskService.create(task);
    }

}
