package com.example.note.note.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 图片处理器（教学模拟版：sleep 700ms 模拟压缩/裁剪/生成缩略图）
 *
 * 真实世界的图片流水线：上传 OSS 原图 → 云函数/worker 拉取 → 压缩出多规格 → 回写 CDN 地址。
 * 共同点：耗时秒级、和「发布笔记」这个动作天然可以解耦 —— Phase 1 挪进 MQ 消费者。
 */
@Component
public class ImageProcessor {

    public List<String> process(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }
        try {
            // 模拟压缩耗时：每张约 200ms（真实 1~3s/张，九图就是十秒级 —— 同步等它纯属自虐）
            Thread.sleep(200L * imageUrls.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return imageUrls.stream()
                .map(url -> url.contains("?") ? url + "&w=800&fmt=webp" : url + "?w=800&fmt=webp")
                .toList();
    }
}
