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
import studykeeper.dto.RestTimeDTO;
import studykeeper.interceptor.JwtInterceptor;
import studykeeper.service.RestTimeService;

import java.util.List;

/**
 * 休息时间接口。
 */
@RestController
@RequestMapping("/api/rest")
public class RestTimeController {

    private final RestTimeService restTimeService;

    public RestTimeController(RestTimeService restTimeService) {
        this.restTimeService = restTimeService;
    }

    /**
     * 休息时段列表。每天生效（dayOfWeek=null）的排最前。
     */
    @GetMapping
    public Result<List<RestTimeDTO>> list(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId) {
        return Result.success(restTimeService.list(userId));
    }

    /**
     * 新增休息时段。参数不合法（dayOfWeek 越界、startTime >= endTime 等）返回 400。
     */
    @PostMapping
    public Result<RestTimeDTO> create(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                      @RequestBody RestTimeDTO request) {
        try {
            return Result.success(restTimeService.create(userId, request));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 修改休息时段，只传要改的字段。参数不合法返回 400，记录不存在或不属于当前用户返回 404。
     */
    @PutMapping("/{id}")
    public Result<RestTimeDTO> update(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                                      @PathVariable Long id, @RequestBody RestTimeDTO request) {
        try {
            RestTimeDTO updated = restTimeService.update(userId, id, request);
            if (updated == null) {
                return Result.error(404, "休息时间不存在");
            }
            return Result.success(updated);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 删除休息时段。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute(JwtInterceptor.USER_ID_ATTRIBUTE) Long userId,
                               @PathVariable Long id) {
        if (!restTimeService.delete(userId, id)) {
            return Result.error(404, "休息时间不存在");
        }
        return Result.success();
    }

}
