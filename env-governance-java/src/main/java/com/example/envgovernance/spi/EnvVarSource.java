package com.example.envgovernance.spi;

import java.util.Map;
import java.util.Set;

/**
 * SPI para fontes plugáveis de variáveis de ambiente — zero dependências de framework.
 * <p>
 * Implementações são descobertas via {@link java.util.ServiceLoader} usando o arquivo
 * {@code META-INF/services/com.example.envgovernance.spi.EnvVarSource}.
 * Cada implementação deve ter um construtor sem argumentos.
 *
 * <h3>Ciclo de vida</h3>
 * <ol>
 *   <li>{@link #isAvailable(Map)} — guarda: se {@code false}, a fonte é ignorada silenciosamente.</li>
 *   <li>{@link #load(Map)} — executa a busca (ex.: chamada HTTP ao Vault). Deve capturar os
 *       nomes de variáveis internamente para que {@link #getVarNames()} possa servi-los depois.</li>
 *   <li>{@link #getVarNames()} — consultado por reporters após {@link #load(Map)} completar.</li>
 * </ol>
 *
 * <h3>Prioridade</h3>
 * Valor menor de {@link #getOrder()} = maior prioridade. Convencional:
 * {@code system-env}=0, {@code vault}=10, terceiros=100.
 *
 * @author Sartre Brasil
 * @since 2.0
 */
public interface EnvVarSource {

	/**
	 * Nome único de exibição, usado em logs e atribuição de relatórios.
	 * Exemplos: {@code "system-env"}, {@code "vault:secret/myapp"}.
	 */
	String name();

	/**
	 * Retorna {@code true} se esta fonte tem configuração mínima para tentativa de
	 * inicialização. Um retorno {@code false} causa o skip silencioso da fonte, sem erro.
	 *
	 * @param environment mapa plano com todas as variáveis disponíveis no momento da verificação
	 */
	boolean isAvailable(Map<String, String> environment);

	/**
	 * Inicializa a fonte e retorna as variáveis que ela provê.
	 * <p>
	 * Retorna mapa vazio se a fonte não injeta novos valores (ex.: {@code SystemEnvVarSource},
	 * cujos dados já estão no ambiente base via {@code System.getenv()}).
	 * Deve armazenar os nomes internamente para que {@link #getVarNames()} possa servi-los.
	 *
	 * @param environment mapa plano com as variáveis disponíveis antes desta fonte ser carregada
	 * @return mapa imutável das variáveis a injetar (vazio = nada a injetar)
	 */
	Map<String, String> load(Map<String, String> environment);

	/**
	 * Retorna todos os nomes de variáveis providos por esta fonte.
	 * O resultado é indefinido antes de {@link #load(Map)} completar.
	 */
	Set<String> getVarNames();

	/**
	 * Retorna {@code true} se o nome de variável é considerado sensível nesta fonte,
	 * usado pelos reporters para mascarar valores na saída.
	 */
	default boolean isSensitive(String varName) {
		String upper = varName.toUpperCase();
		return upper.contains("PASSWORD") || upper.contains("SECRET")
				|| upper.contains("TOKEN")    || upper.contains("KEY")
				|| upper.contains("CREDENTIAL");
	}

	/**
	 * Prioridade: valor menor = maior prioridade.
	 * {@code SystemEnvVarSource}: 0. {@code VaultEnvVarSource}: 10. Padrão para terceiros: 100.
	 */
	int getOrder();
}
