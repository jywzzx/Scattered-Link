package lanlink.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 输出器，与 lanlink.core.Json（解析）互补。
 *
 * 为什么不再引一个 Gson/Jackson：项目一直是零外部依赖，打包体积也敏感。
 * 我们只需要输出 Map / List / String / Number / Boolean / null 这几种形态，
 * 一百行足够，且完全可控（例如不受第三方库默认转义策略变化影响）。
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder(512);
        put(sb, v);
        return sb.toString();
    }

    /** 便捷构造：of("k1", v1, "k2", v2, …) */
    public static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 可变 map，便于逐项 put */
    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    private static void put(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof String) {
            str(sb, (String) v);
            return;
        }
        if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
            return;
        }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                put(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                put(sb, o);
            }
            sb.append(']');
            return;
        }
        if (v.getClass().isArray()) {
            sb.append('[');
            int n = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                put(sb, java.lang.reflect.Array.get(v, i));
            }
            sb.append(']');
            return;
        }
        if (v instanceof List) {
            // 理论上被上面的 Iterable 分支覆盖，留作兜底
            put(sb, ((List<?>) v).toArray());
            return;
        }
        str(sb, String.valueOf(v));
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    // 控制字符必须转义，否则 JSON.parse 会失败
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
