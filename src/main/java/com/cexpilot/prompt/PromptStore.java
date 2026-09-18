package com.cexpilot.prompt;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * prompt 模板加载与渲染（classpath:prompts/*.txt，{{占位符}} 替换）。
 * 内容 MD5 前 8 位作为 prompt_version 落库，便于回溯"当时用的是哪版 prompt"。
 */
@Component
public class PromptStore {

    private final ResourceLoader resourceLoader;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<String, String> versions = new ConcurrentHashMap<>();

    public PromptStore(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String render(String name, Map<String, String> vars) {
        String template = load(name);
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return template;
    }

    public String version(String name) {
        return versions.computeIfAbsent(name, n -> md5Prefix(load(n)));
    }

    private String load(String name) {
        return cache.computeIfAbsent(name, n -> {
            Resource resource = resourceLoader.getResource("classpath:prompts/" + n + ".txt");
            try {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("prompt 模板不存在: " + n, e);
            }
        });
    }

    private static String md5Prefix(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
