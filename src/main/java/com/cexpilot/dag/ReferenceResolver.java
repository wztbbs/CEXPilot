package com.cexpilot.dag;

import com.cexpilot.runtime.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析节点 args 中的 {{nodeId.path.to.field}} 引用：
 * - 字符串整体是一个引用（全串匹配）→ 替换为上游 ToolResult 对应路径的原始 JsonNode，保持 JSON 类型；
 * - 引用嵌在长字符串中 → 按文本插值（文本值取 asText，其余取 JSON 序列化）；
 * - path 为点分隔字段/下标，内部转 JSON Pointer 用 Jackson .at() 解析；
 *   根对象为 {"data": 上游产出, "error": 上游错误}，故 {{n1.data.txHash}} 取 n1 产出的 txHash 字段。
 * 引用缺失、路径不存在或上游失败时抛 ReferenceResolutionException。
 */
public final class ReferenceResolver {

    private static final Pattern FULL_REF =
            Pattern.compile("^\\{\\{([A-Za-z0-9_]+)((?:\\.[A-Za-z0-9_]+)*)}}$");
    private static final Pattern EMBEDDED_REF =
            Pattern.compile("\\{\\{([A-Za-z0-9_]+)((?:\\.[A-Za-z0-9_]+)*)}}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReferenceResolver() {
    }

    /** 深度复制 args 并替换其中所有引用，产出可直接传给工具的入参。 */
    public static JsonNode resolve(JsonNode args, DagContext ctx) {
        if (args == null) {
            return null;
        }
        if (args.isTextual()) {
            return resolveText(args.asText(), ctx);
        }
        if (args.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            var fields = args.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                copy.set(entry.getKey(), resolve(entry.getValue(), ctx));
            }
            return copy;
        }
        if (args.isArray()) {
            var copy = MAPPER.createArrayNode();
            for (JsonNode element : args) {
                copy.add(resolve(element, ctx));
            }
            return copy;
        }
        return args.deepCopy();
    }

    /** 扫描 args 中出现的全部引用（nodeId + 点分隔路径），供 PlanValidator 做静态校验。 */
    public static List<Ref> findRefs(JsonNode args) {
        List<Ref> refs = new ArrayList<>();
        collectRefs(args, refs);
        return refs;
    }

    private static void collectRefs(JsonNode node, List<Ref> refs) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            Matcher matcher = EMBEDDED_REF.matcher(node.asText());
            while (matcher.find()) {
                refs.add(new Ref(matcher.group(1), matcher.group(2)));
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectRefs(entry.getValue(), refs));
        } else if (node.isArray()) {
            node.forEach(element -> collectRefs(element, refs));
        }
    }

    private static JsonNode resolveText(String text, DagContext ctx) {
        Matcher full = FULL_REF.matcher(text);
        if (full.matches()) {
            return resolveRef(full.group(1), full.group(2), ctx);
        }
        Matcher embedded = EMBEDDED_REF.matcher(text);
        if (!embedded.find()) {
            return TextNode.valueOf(text);
        }
        StringBuilder sb = new StringBuilder();
        embedded.reset();
        while (embedded.find()) {
            JsonNode value = resolveRef(embedded.group(1), embedded.group(2), ctx);
            String replacement = value.isTextual() ? value.asText() : value.toString();
            embedded.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        embedded.appendTail(sb);
        return TextNode.valueOf(sb.toString());
    }

    private static JsonNode resolveRef(String nodeId, String path, DagContext ctx) {
        ToolResult result = ctx.get(nodeId);
        if (result == null) {
            throw new ReferenceResolutionException("引用目标不存在或尚未执行: " + nodeId);
        }
        if (!result.ok()) {
            throw new ReferenceResolutionException("引用目标节点执行失败: " + nodeId);
        }
        JsonNode data = result.data() == null ? NullNode.getInstance() : result.data();
        if (path == null || path.isEmpty()) {
            return data;
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("data", data);
        root.put("error", result.error());
        JsonNode value = root.at(toPointer(path));
        if (value.isMissingNode()) {
            throw new ReferenceResolutionException("引用路径不存在: {{" + nodeId + path + "}}");
        }
        return value;
    }

    /** 点分隔路径（.data.txHash / .data.0.hash）转 JSON Pointer（/data/txHash / /data/0/hash）。 */
    private static String toPointer(String dotPath) {
        StringBuilder pointer = new StringBuilder();
        for (String segment : dotPath.substring(1).split("\\.")) {
            pointer.append('/').append(segment.replace("~", "~0").replace("/", "~1"));
        }
        return pointer.toString();
    }

    /** 一条引用：目标节点 id + 点分隔路径（无前导点为空串）。 */
    public record Ref(String nodeId, String path) {
    }

    /** 引用在运行期无法解析（目标缺失 / 路径不存在 / 上游失败）。 */
    public static class ReferenceResolutionException extends RuntimeException {
        public ReferenceResolutionException(String message) {
            super(message);
        }
    }
}
