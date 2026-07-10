package com.example.envgovernance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Envolve todos os {@link PropertySource} existentes com {@link TrackingPropertySource}
 * para que cada leitura de propriedade seja registrada em {@link UsedPropertiesRegistry}.
 * Roda por último (LOWEST_PRECEDENCE) para garantir que todas as sources já foram adicionadas.
 *
 * @author Sartre Brasil
 * @since 1.0
 */
public class UsageTrackingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	// Spring Boot registra internamente uma ConfigurationPropertySourcesPropertySource com
	// esse nome e faz cast direto ao recuperá-la — não podemos substituí-la.
	private static final String SPRING_INTERNAL_SOURCE = "configurationProperties";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		MutablePropertySources sources = environment.getPropertySources();
		for (PropertySource<?> source : sources) {
			if (!(source instanceof TrackingPropertySource)
					&& !SPRING_INTERNAL_SOURCE.equals(source.getName())) {
				sources.replace(source.getName(), TrackingPropertySource.wrap(source));
			}
		}
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
