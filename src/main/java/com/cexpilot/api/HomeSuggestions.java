package com.cexpilot.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;

/** 启动时读取配置快照；请求只操作副本，不监听文件、不调用模型。 */
@Service
public class HomeSuggestions {
    public record Suggestion(String id, String category, String query) {}
    private final List<Suggestion> entries;

    public HomeSuggestions(@Value("${home.suggestions-location:classpath:home-suggestions.yml}") Resource resource) throws java.io.IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var input=resource.getInputStream()) {
            Object loaded=new Yaml(new SafeConstructor(options)).load(input);
            if (!(loaded instanceof Map<?,?> root) || !(root.get("queries") instanceof List<?> items) || items.isEmpty())
                throw new IllegalArgumentException("推荐问题配置必须包含非空 queries");
            var result=new ArrayList<Suggestion>(); var ids=new HashSet<String>();
            for(Object item:items) {
                if(!(item instanceof Map<?,?> values)) throw new IllegalArgumentException("推荐问题格式无效");
                String id=required(values,"id"), category=required(values,"category"), query=required(values,"query");
                if(!ids.add(id)) throw new IllegalArgumentException("推荐问题 id 重复："+id);
                result.add(new Suggestion(id,category,query));
            }
            entries=List.copyOf(result);
        }
    }
    private static String required(Map<?,?> values,String key) {
        if(!(values.get(key) instanceof String text) || text.isBlank()) throw new IllegalArgumentException("推荐问题缺少 "+key);
        return text.trim();
    }
    public List<Suggestion> select() {
        var shuffled=new ArrayList<>(entries); Collections.shuffle(shuffled);
        var chosen=new ArrayList<Suggestion>(); var categories=new HashSet<String>();
        for(var entry:shuffled) if(categories.add(entry.category()) && chosen.size()<4) chosen.add(entry);
        for(var entry:shuffled) if(chosen.size()<4 && !chosen.contains(entry)) chosen.add(entry);
        return List.copyOf(chosen);
    }
    @RestController
    public static class Controller {
        private final HomeSuggestions suggestions;
        public Controller(HomeSuggestions suggestions) { this.suggestions=suggestions; }
        @GetMapping("/api/home/suggestions")
        public List<Suggestion> get(jakarta.servlet.http.HttpServletResponse response) {
            response.setHeader("Cache-Control","no-store");
            return suggestions.select();
        }
    }
}
