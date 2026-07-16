package com.example.envgovernance;

import com.example.envgovernance.contract.ConditionalRequirement;
import com.example.envgovernance.contract.EnvContract;
import com.example.envgovernance.contract.EnvContractLoader;
import com.example.envgovernance.contract.VarSpec;
import com.example.envgovernance.spi.EnvVarSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Carrega o contrato declarativo de configuração do classpath e o armazena no
 * {@link ContractRegistry} para uso pelos processadores seguintes.
 *
 * <p>Ordem {@code HIGHEST_PRECEDENCE + 16}: após o {@code DeclaredVarsScanner} (+15),
 * antes do {@code EnvVarSourceLoaderPostProcessor} (+18) e do
 * {@code RequiredEnvCheckEnvironmentPostProcessor} (+20), de forma que o contrato
 * esteja disponível quando as checagens de obrigatórias rodarem.
 *
 * <p>Tenta os locais padrão em ordem ({@code env-governance.properties}, depois
 * {@code env-governance.yml}) usando os parsers descobertos via {@link java.util.ServiceLoader}.
 * Retorna silenciosamente se nenhum arquivo for encontrado.
 *
 * @author Sartre Brasil
 * @since 2.2
 * @see ContractRegistry
 * @see EnvContractLoader
 */
public class EnvContractScanner implements EnvironmentPostProcessor, Ordered {

	private static final Log log = LogFactory.getLog(EnvContractScanner.class);

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!environment.getProperty("env.governance.enabled", Boolean.class, true)) {
			return;
		}

		ContractRegistry.reset();

		ClassLoader classLoader = application.getClassLoader() != null
				? application.getClassLoader()
				: Thread.currentThread().getContextClassLoader();

		EnvContract contract = EnvContractLoader.loadFromClasspath(classLoader);

		// Merge de contribuições das fontes SPI (arquivo/builder têm precedência)
		List<VarSpec> spiSpecs = new ArrayList<>();
		List<ConditionalRequirement> spiConds = new ArrayList<>();
		ServiceLoader.load(EnvVarSource.class, classLoader).forEach(source -> {
			spiSpecs.addAll(source.contributedSpecs());
			spiConds.addAll(source.contributedConditionals());
		});
		if (!spiSpecs.isEmpty() || !spiConds.isEmpty()) {
			contract = contract.merge(new EnvContract(spiSpecs, spiConds));
			log.debug("[ENV GOVERNANCE] SPI contributions: "
					+ spiSpecs.size() + " spec(s), "
					+ spiConds.size() + " condicional(is)");
		}

		ContractRegistry.setContract(contract);

		if (!contract.isEmpty()) {
			log.debug("[ENV GOVERNANCE] Contrato declarativo carregado: "
					+ contract.specs().size() + " spec(s), "
					+ contract.conditionals().size() + " condicional(is)");
		}
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 16;
	}
}
