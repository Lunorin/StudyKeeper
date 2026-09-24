package studykeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import studykeeper.entity.TaskTemplate;

/**
 * task_template 表 Mapper，通用 CRUD 由 MyBatis-Plus 提供。
 */
@Mapper
public interface TaskTemplateMapper extends BaseMapper<TaskTemplate> {

}
