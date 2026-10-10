package com.cexpilot.metric;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MetricProviderRegistry {
    private final Map<String, MetricProvider> providers;

    @org.springframework.beans.factory.annotation.Autowired
    public MetricProviderRegistry(List<MetricProvider> providers, MetricCatalog catalog) {
        this(providers);
        validate(catalog);
    }

    public MetricProviderRegistry(List<MetricProvider> providers) {
        Map<String, MetricProvider> byName = new HashMap<>();
        for (MetricProvider provider : providers) {
            if (byName.putIfAbsent(provider.name(), provider) != null) {
                throw new IllegalStateException("重复指标 Provider: " + provider.name());
            }
        }
        this.providers = Map.copyOf(byName);
    }

    public MetricProvider get(String name) {
        MetricProvider provider = providers.get(name);
        if (provider == null) throw new IllegalArgumentException("未注册指标 Provider: " + name);
        return provider;
    }

    /** 校验绑定指向已注册 Provider、选择器可解析且支持对应查询形态。 */
    public void validate(MetricCatalog catalog) {
        for (String metric : catalog.names()) {
            catalog.metricDefinition(metric).bindings().forEach((shape, binding) -> {
                MetricProvider provider = get(binding.provider());
                MetricSelector selector;
                try {
                    selector = provider.selector(binding.selector());
                } catch (IllegalArgumentException ex) {
                    throw new IllegalStateException("指标绑定不可用: " + metric + "/" + shape.code(), ex);
                }
                if (!selector.supports(shape.code())) {
                    throw new IllegalStateException("指标绑定不可用: " + metric + "/" + shape.code());
                }
            });
        }
    }
}
