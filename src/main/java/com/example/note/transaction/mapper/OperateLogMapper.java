package com.example.note.transaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.note.transaction.entity.OperateLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperateLogMapper extends BaseMapper<OperateLog> {
}
