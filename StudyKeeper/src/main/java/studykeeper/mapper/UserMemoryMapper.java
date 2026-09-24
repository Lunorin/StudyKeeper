package studykeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import studykeeper.entity.UserMemory;

/**
 * user_memory 表 Mapper，通用 CRUD 由 MyBatis-Plus 提供。
 */
@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemory> {

}
