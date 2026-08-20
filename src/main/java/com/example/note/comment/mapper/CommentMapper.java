package com.example.note.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.note.comment.entity.Comment;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}
