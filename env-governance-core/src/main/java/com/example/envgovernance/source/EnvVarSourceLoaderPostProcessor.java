package com.example.envgovernance.source;

import com.example.envgovernance.spi.EnvVarSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Descobre, ordena e inicializa todas as implementações de {@link EnvVarSource} declaradas
 * via {@code spring.factories}, injetando no {@link ConfigurableEnvironment} os
 * {@link PropertySource}s retornados por cada fonte.
 *
 * <p>Ordem {@code HIGHEST_PRECEDENCE + 18}: após o {@code DeclaredVarsScanner} (+15) e
 * antes do {@code RequiredEnvCheckEnvironmentPostProcessor} (+20), garantindo que as
 * variáveis do Vault estejam disponíveis quando a checagem de obrigatórias rodar.
 *
 * <p>O comportamento em caso de falha de uma fonte é controlado por
 * {@code env.governance.sources.<nome>.on-failure}:
 * <ul>
 *   <li>{@code fail} (padrão) — relança a exceção, abortando o startup.</li>
 *   <li>{@code warn} — registra aviso, continua, registra a fonte (com {@code getVarNames()} vazio).</li>
 *   <li>{@code skip} — registra aviso, continua, não registra a fonte.</li>
 * </ul>
 *
 * @author Sartre Brasil
 * @since 1.1
 * @see EnvVarSource
 * @see EnvVarSourceRegistry
 */
public class EnvVarSourceLoaderPostProcessor implements EnvironmentPostProcessor, Ordered {

	private static final Log log = LogFactory.getLog(EnvVarSourceLoaderPostProcessor.class);

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!environment.getProperty("env.governance.enabled", Boolean.class, true)) {
			return;
		}

		EnvVarSourceRegistry.reset();

		ClassLoader classLoader = application.getClassLoader() != null
				? application.getClassLoader()
				: Thread.currentThread().getContextClassLoader();

		List<EnvVarSource> sources = new ArrayList<>(loadSources(classLoader));
		sources.sort(Comparator.comparingInt(Ordered::getOrder));

		MutablePropertySources propertySources = environment.getPropertySources();
		// Fontes remotas são inseridas após systemEnvironment — o S.O. tem prioridade máxima
		String insertAfterName = "systemEnvironment";

		for (EnvVarSource source : sources) {
			if (!source.isAvailable(environment)) {
				log.debug("[ENV GOVERNANCE] Fonte indisponível, ignorada: " + source.name());
				continue;
			}

			String onFailure = environment.getProperty(
					"env.governance.sources." + source.name() + ".on-failure", "fail");

			try {
				Optional<PropertySource<?>> ps = source.load(environment);
				if (ps.isPresent()) {
					PropertySource<?> propertySource = ps.get();
					if (propertySources.contains(insertAfterName)) {
						propertySources.addAfter(insertAfterName, propertySource);
					} else {
						propertySources.addLast(propertySource);
					}
					insertAfterName = propertySource.getName();
					log.debug("[ENV GOVERNANCE] Fonte '" + source.name()
							+ "' carregada (" + source.getVarNames().size() + " variáveis)");
				}
				EnvVarSourceRegistry.register(source);

			} catch (Exception ex) {
				if ("fail".equalsIgnoreCase(onFailure)) {
					throw new EnvVarSourceLoadException(
							"Falha ao inicializar EnvVarSource '" + source.name() + "'", ex);
				}
				log.warn("[ENV GOVERNANCE] Fonte '" + source.name()
						+ "' falhou ao carregar (on-failure=" + onFailure + "): " + ex.getMessage());
				if (!"skip".equalsIgnoreCase(onFailure)) {
					EnvVarSourceRegistry.register(source);
				}
			}
		}
	}

	/**
	 * Carrega as implementações de {@link EnvVarSource} do classpath.
	 * Método protegido para permitir substituição em testes sem manipulação de classloader.
	 */
	protected List<EnvVarSource> loadSources(ClassLoader classLoader) {
		return SpringFactoriesLoader.loadFactories(EnvVarSource.class, classLoader);
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 18;
	}
}
