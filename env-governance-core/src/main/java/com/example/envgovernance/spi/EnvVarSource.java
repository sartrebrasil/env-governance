package com.example.envgovernance.spi;

import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.util.Optional;
import java.util.Set;

/**
 * SPI para fontes plugáveis de variáveis de ambiente.
 * <p>
 * Implementações são descobertas via {@code spring.factories} sob a chave do nome
 * qualificado desta interface. Cada implementação deve ter um construtor sem argumentos.
 *
 * <h3>Ciclo de vida</h3>
 * Chamadas feitas exclusivamente por {@link com.example.envgovernance.source.EnvVarSourceLoaderPostProcessor}
 * na ordem abaixo:
 * <ol>
 *   <li>{@link #isAvailable(ConfigurableEnvironment)} — guarda: se {@code false}, a fonte é silenciosamente ignorada.</li>
 *   <li>{@link #load(ConfigurableEnvironment)} — conecta/busca; opcionalmente injeta um {@link PropertySource}
 *       no {@code Environment}. Deve capturar os nomes de variáveis internamente.</li>
 *   <li>{@link #getVarNames()} — consultado pelos reporters no evento {@code ApplicationReadyEvent}.</li>
 * </ol>
 *
 * @author Sartre Brasil
 * @since 1.1
 */
public interface EnvVarSource extends Ordered {

	/**
	 * Nome único de exibição, usado em logs e atribuição de relatórios.
	 * Exemplos: {@code "system-env"}, {@code "vault[secret/myapp]"}.
	 */
	String name();

	/**
	 * Retorna {@code true} se esta fonte tem configuração mínima para tentativa de
	 * inicialização. Um retorno {@code false} causa o skip silencioso da fonte, sem erro.
	 */
	boolean isAvailable(ConfigurableEnvironment environment);

	/**
	 * Inicializa a fonte. Para fontes remotas (Vault, AWS SM) é aqui que a chamada de
	 * rede acontece — de forma síncrona, na fase EPP.
	 * <p>
	 * Retorna {@link Optional#empty()} se nenhum {@link PropertySource} precisa ser
	 * injetado (ex.: {@code SystemEnvVarSource}, cujos dados já estão no ambiente).
	 * Retorna {@link Optional#of(Object)} para que o loader insira o {@link PropertySource}
	 * no ambiente imediatamente após {@code "systemEnvironment"} na ordem de prioridade.
	 * <p>
	 * DEVE armazenar os nomes de variáveis buscados internamente para que
	 * {@link #getVarNames()} possa servi-los depois.
	 * <p>
	 * O comportamento em caso de falha é governado por
	 * {@code env.governance.sources.<name>.on-failure}: {@code "fail"} (padrão) relança
	 * a exceção abortando o startup; {@code "warn"} registra aviso e continua;
	 * {@code "skip"} registra aviso e não registra a fonte.
	 */
	Optional<PropertySource<?>> load(ConfigurableEnvironment environment);

	/**
	 * Retorna todos os nomes de variáveis providos por esta fonte.
	 * O resultado é indefinido antes de {@link #load(ConfigurableEnvironment)} completar.
	 */
	Set<String> getVarNames();

	/**
	 * Retorna {@code true} se o nome de variável é considerado sensível nesta fonte,
	 * usado pelos reporters para suprimir ou mascarar valores na saída.
	 * <p>
	 * Implementação padrão: verifica tokens de dica comuns no nome da variável.
	 */
	default boolean isSensitive(String varName) {
		String upper = varName.toUpperCase();
		return upper.contains("PASSWORD") || upper.contains("SECRET")
				|| upper.contains("TOKEN")    || upper.contains("KEY")
				|| upper.contains("CREDENTIAL");
	}

	/**
	 * Prioridade: valor menor = maior prioridade. Mesma semântica do {@link Ordered}.
	 * {@code SystemEnvVarSource}: 0. {@code VaultEnvVarSource}: 10. Padrão para terceiros: 100.
	 */
	@Override
	int getOrder();
}
