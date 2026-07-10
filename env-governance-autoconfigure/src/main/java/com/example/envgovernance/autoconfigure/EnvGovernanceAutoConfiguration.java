package com.example.envgovernance.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuração principal do env-governance.
 *
 * <p>Registra automaticamente {@link EnvVarUsageReporter} e, se {@code spring-boot-actuator}
 * estiver no classpath, também o {@link EnvGovernanceEndpoint} em {@code /actuator/env-governance}.
 *
 * <p>Toda a lib pode ser desabilitada via {@code env.governance.enabled=false}.
 *
 * @author Sartre Brasil
 * @since 1.0
 */
@AutoConfiguration
@EnableConfigurationProperties(EnvGovernanceProperties.class)
@ConditionalOnProperty(prefix = "env.governance", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EnvGovernanceAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public EnvVarUsageReporter envVarUsageReporter(EnvGovernanceProperties properties) {
		return new EnvVarUsageReporter(properties);
	}

	/**
	 * Registrado apenas quando {@code spring-boot-actuator} está no classpath.
	 * A condição usa string para evitar ClassNotFoundException no bootstrap.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
	static class ActuatorConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public EnvGovernanceEndpoint envGovernanceEndpoint() {
			return new EnvGovernanceEndpoint();
		}
	}
}
