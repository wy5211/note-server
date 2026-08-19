package com.example.note.follow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.note.follow.entity.Follow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 关注关系 Mapper —— 注解 SQL 示范（对照 NoteLikeMapper 的 XML 写法）
 *
 * 国内两种手写 SQL 风格并存：
 *   XML：复杂动态 SQL（foreach 大批量、多分支）可读性好 —— NoteLikeMapper
 *   注解：一两句简单 SQL 零文件开销，就近可读 —— 本类
 * <script> 标签让注解里也能用动态 SQL（写法比 XML 别扭，所以复杂的还是建议 XML）
 */
@Mapper
public interface FollowMapper extends BaseMapper<Follow> {

    /** 关注（幂等）：撞 uk_follower_following 静默跳过 */
    @Insert("INSERT IGNORE INTO follow (follower_id, following_id, created_at) VALUES " +
            "(#{followerId}, #{followingId}, NOW())")
    int insertIgnore(@Param("followerId") Long followerId, @Param("followingId") Long followingId);

    /** 测试造数用：批量插粉丝（给一位博主造 N 个关注者） */
    @Insert("<script>" +
            "INSERT IGNORE INTO follow (follower_id, following_id, created_at) VALUES " +
            "<foreach collection='followerIds' item='fid' separator=','>" +
            "(#{fid}, #{followingId}, NOW())" +
            "</foreach>" +
            "</script>")
    int insertFans(@Param("followerIds") List<Long> followerIds, @Param("followingId") Long followingId);

    /**
     * 我关注的人里，哪些是「大 V」（粉丝数超阈值）—— Feed 拉模式的数据源。
     * 真实微博不这么实时 GROUP BY（慢），而是离线算好「大 V 标记」冗余在关系表 ——
     * 这里保持教学透明，注释留档
     */
    @Select("SELECT following_id FROM follow WHERE follower_id = #{userId} " +
            "AND following_id IN " +
            "(SELECT following_id FROM follow GROUP BY following_id HAVING COUNT(*) > #{threshold})")
    List<Long> selectBigVFollowings(@Param("userId") Long userId, @Param("threshold") int threshold);
}
