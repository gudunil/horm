package com.holo.framework.horm.examples.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.holo.framework.horm.examples.entity.MpUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus 用户 Mapper。
 */
@Mapper
public interface MpUserMapper extends BaseMapper<MpUser> {
}
