package studykeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import studykeeper.entity.Feedback;

/**
 * feedback 表 Mapper，通用 CRUD 由 MyBatis-Plus 提供。
 * <p>
 * 目前只用到 insert（POST /api/user/feedback），不做列表查询。
 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {

}
