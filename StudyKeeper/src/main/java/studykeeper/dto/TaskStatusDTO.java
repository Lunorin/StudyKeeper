package studykeeper.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * done / undo 接口返回的业务数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusDTO {

    private Long id;

    private String status;

    /** 未完成时为 null */
    private LocalDateTime completedAt;

}
