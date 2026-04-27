package github.lms.lemuel.settlement.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
@Configuration
@EnableElasticsearchRepositories(basePackages = "github.lms.lemuel.settlement.adapter.out.search")
public class ElasticsearchConfig {
}
