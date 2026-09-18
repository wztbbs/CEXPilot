package com.cexpilot.eval;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 classpath:evals/*.yaml 加载评测 case。
 */
@Component
public class EvalCaseLoader {

    private static final String LOCATION_PATTERN = "classpath:evals/*.yaml";

    public List<EvalCase> load(String category) {
        List<EvalCase> cases = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(LOCATION_PATTERN);
            for (Resource resource : resources) {
                String fileName = resource.getFilename() == null ? "" : resource.getFilename();
                String fileCategory = fileName.replace(".yaml", "");
                if (category != null && !category.isBlank() && !fileCategory.equals(category)) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    Map<String, Object> doc = new Yaml().load(in);
                    List<Map<String, Object>> items = (List<Map<String, Object>>) doc.get("cases");
                    if (items == null) {
                        continue;
                    }
                    for (Map<String, Object> item : items) {
                        cases.add(new EvalCase(
                                (String) item.get("id"),
                                fileCategory,
                                (String) item.get("question"),
                                stringList(item.get("setup_questions")),
                                stringList(item.get("expected_tools")),
                                stringList(item.get("forbidden_tools")),
                                stringList(item.get("required_evidence_keys"))));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载 eval case 失败: " + e.getMessage(), e);
        }
        return cases;
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        return ((List<?>) value).stream().map(String::valueOf).toList();
    }
}
