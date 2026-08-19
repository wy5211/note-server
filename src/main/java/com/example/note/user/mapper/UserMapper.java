package com.example.note.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.note.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * BaseMapper 自带 insert/selectById/selectOne/update 等十七件套 ≈ PrismaClient.user.xxx
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
