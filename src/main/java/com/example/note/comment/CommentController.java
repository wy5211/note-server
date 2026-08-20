package com.example.note.comment;

import com.example.note.common.Result;
import com.example.note.security.CurrentUser;
import com.example.note.security.LoginUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @PostMapping("/api/notes/{noteId}/comments")
    public Result<Map<String, Long>> create(@CurrentUser LoginUser user,
                                            @PathVariable Long noteId,
                                            @RequestBody CommentCreateDTO dto) {
        Long id = commentService.create(user.userId(), noteId, dto.getContent());
        return Result.ok(Map.of("commentId", id));
    }

    @Data
    public static class CommentCreateDTO {
        @NotBlank(message = "评论内容不能为空")
        @Size(max = 500, message = "评论最长 500 字")
        private String content;
    }
}
