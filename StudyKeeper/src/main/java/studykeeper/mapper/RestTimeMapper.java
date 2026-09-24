package studykeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import studykeeper.entity.RestTime;

/**
 * rest_time 表 Mapper，通用 CRUD 由 MyBatis-Plus 提供。
 */
@Mapper
public interface RestTimeMapper extends BaseMapper<RestTime> {

}
