package com.cexpilot.api;

import com.cexpilot.prompt.PromptStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;

/** 启动时读取配置快照；固定项按配置顺序在前，其余按类别随机补足四条。 */
@Service
public class HomeSuggestions {
    public record Suggestion(String id, String category, String query, boolean pinned) {}
    public record FixedReply(String id, String query, String answer) {}
    private record Entry(Suggestion suggestion, String answer) {}
    private final List<Entry> entries;
    private final String version;

    public HomeSuggestions(@Value("${home.suggestions-location:classpath:home-suggestions.yml}") Resource resource) throws java.io.IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var input=resource.getInputStream()) {
            Object loaded=new Yaml(new SafeConstructor(options)).load(input);
            if (!(loaded instanceof Map<?,?> root) || !(root.get("queries") instanceof List<?> items) || items.isEmpty())
                throw new IllegalArgumentException("推荐问题配置必须包含非空 queries");
            var result=new ArrayList<Entry>(); var ids=new HashSet<String>();
            for(Object item:items) {
                if(!(item instanceof Map<?,?> values)) throw new IllegalArgumentException("推荐问题格式无效");
                String id=required(values,"id"), category=required(values,"category"), query=required(values,"query");
                if(!ids.add(id)) throw new IllegalArgumentException("推荐问题 id 重复："+id);
                Object pinned=values.get("pinned");
                if(pinned!=null && !(pinned instanceof Boolean)) throw new IllegalArgumentException("pinned 必须为布尔值");
                String answer=values.get("answer")==null?null:required(values,"answer");
                result.add(new Entry(new Suggestion(id,category,query,Boolean.TRUE.equals(pinned)),answer));
            }
            entries=List.copyOf(result);
            version=PromptStore.fingerprint(entries.toString());
        }
    }
    private static String required(Map<?,?> values,String key) {
        if(!(values.get(key) instanceof String text) || text.isBlank()) throw new IllegalArgumentException("推荐问题缺少 "+key);
        return text.trim();
    }
    public String version() { return version; }

    /** 仅匹配完整配置问题；容忍末尾问号/句号，不把包含“功能”的其他问题拦截。 */
    public Optional<FixedReply> fixedReply(String question) {
        if(question==null) return Optional.empty();
        String normalized=normalize(question);
        return entries.stream().filter(e->e.answer()!=null && normalize(e.suggestion().query()).equals(normalized))
                .map(e->new FixedReply(e.suggestion().id(),e.suggestion().query(),e.answer())).findFirst();
    }
    private static String normalize(String text) { return text.strip().replaceFirst("[？?。.!！]+$", "").strip(); }

    public List<Suggestion> select() {
        var chosen=new ArrayList<Suggestion>(); var categories=new HashSet<String>();
        entries.stream().map(Entry::suggestion).filter(Suggestion::pinned).limit(4).forEach(s->{chosen.add(s);categories.add(s.category());});
        var shuffled=new ArrayList<>(entries.stream().map(Entry::suggestion).filter(s->!s.pinned()).toList());
        Collections.shuffle(shuffled);
        for(var entry:shuffled) if(chosen.size()<4 && categories.add(entry.category())) chosen.add(entry);
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
