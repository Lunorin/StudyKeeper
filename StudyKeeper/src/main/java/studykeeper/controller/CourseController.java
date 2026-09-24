package studykeeper.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import studykeeper.common.Result;
import studykeeper.dto.CourseDTO;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.service.CourseService;

import java.util.List;

/**
 * 课程表接口。
 */
@RestController
@RequestMapping("/api/course")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    /**
     * 课程列表。dayOfWeek 不传则返回全部。
     */
    @GetMapping
    public Result<List<CourseDTO>> list(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                        @RequestParam(required = false) Integer dayOfWeek) {
        return Result.success(courseService.list(userId, dayOfWeek));
    }

    /**
     * 新增课程。参数不合法（dayOfWeek 越界、startTime >= endTime 等）返回 400。
     */
    @PostMapping
    public Result<CourseDTO> create(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                    @RequestBody CourseDTO request) {
        try {
            return Result.success(courseService.create(userId, request));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 修改课程，只传要改的字段。参数不合法返回 400，课程不存在或不属于当前用户返回 404。
     */
    @PutMapping("/{id}")
    public Result<CourseDTO> update(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                    @PathVariable Long id, @RequestBody CourseDTO request) {
        try {
            CourseDTO updated = courseService.update(userId, id, request);
            if (updated == null) {
                return Result.error(404, "课程不存在");
            }
            return Result.success(updated);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 删除课程。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                               @PathVariable Long id) {
        if (!courseService.delete(userId, id)) {
            return Result.error(404, "课程不存在");
        }
        return Result.success();
    }

}
