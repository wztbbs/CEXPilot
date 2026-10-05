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
        catalog.validateBindings();
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

    public void validate(MetricCatalog catalog) {
        for (String metric : catalog.names()) {
            catalog.definition(metric).path("bindings").forEach(binding -> get(binding.path("provider").asText()));
        }
    }
}
