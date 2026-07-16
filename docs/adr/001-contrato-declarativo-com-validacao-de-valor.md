# ADR-001: Contrato declarativo de configuração com validação de valor e requisitos condicionais

- **Date**: 2026-07-14
- **Status**: Accepted (implementado — ver [plano de implementação](../plans/completed/001-contrato-declarativo.md))
- **Deciders**: Sartre Brasil
- **Tags**: architecture, configuration, validation, spi

## Context and Problem Statement

Hoje o `env-governance` deriva o "contrato" de configuração de forma **implícita**: no Spring Boot, escaneia placeholders (`${VAR}`) e chaves das `PropertySources`; no runtime Java puro, o contrato é a sequência de chamadas `require("VAR")` no `GovernanceContext`. Em ambos os casos, o único critério é **presença** — a variável existe em alguma fonte ou não.

Isso deixa três lacunas de governança sem cobertura:

1. **Validação de valor** — uma variável pode estar presente e ainda assim ser inválida (`PORT=abc`, `DB_URL=""`, um enum fora do conjunto permitido). O lib aprova o startup e a falha reaparece em runtime, no ponto de maior custo.
2. **Requisitos condicionais / cross-field** — regras como "se `VAULT_AUTH_METHOD=approle`, então `ROLE_ID` e `SECRET_ID` são obrigatórios" existem hoje apenas *ad hoc*, embutidas no código do módulo Vault. Não há mecanismo geral e reaproveitável.
3. **Contrato explícito** — como o contrato é inferido, não há um artefato versionável que declare *o que a aplicação espera* (nome, obrigatoriedade, tipo, valores permitidos, descrição, sensibilidade). Sem ele, não é possível validar variáveis que ainda não aparecem no YAML, gerar documentação, nem comparar ambientes.

A questão: **como evoluir de "detector de variável ausente no startup" para "camada de governança que valida presença E conteúdo, com regras condicionais, a partir de um contrato explícito"** — mantendo compatibilidade retroativa e o princípio de zero dependências do `env-governance-java`.

## Decision Drivers

- **Retrocompatibilidade total** — apps que só usam detecção de presença hoje não podem quebrar; o contrato explícito deve ser opcional e aditivo.
- **Paridade Spring / não-Spring** — a mesma capacidade deve valer para `GovernanceContext` (Java puro) e para o pipeline de `EnvironmentPostProcessor` do Spring Boot.
- **Zero dependências no núcleo** — `env-governance-java` continua apenas JDK 21; nada de bibliotecas de validação externas (sem Jakarta Bean Validation como dependência obrigatória).
- **Fonte única de verdade** — evitar que o contrato viva duplicado entre o `application.yml` e um novo artefato, gerando divergência.
- **Habilitar o roadmap** — a decisão precisa servir de base para enforcement em build/CI e geração de documentação (decisões futuras), sem pré-decidi-las.

## Considered Options

- **Opção A** — Contrato declarativo explícito (`env-governance.yml`) + API fluente de validação, com um motor de validação compartilhado no `env-governance-java`.
- **Opção B** — Estender apenas a API fluente do `GovernanceContext` (`.asInt()`, `.oneOf(...)`, `requireIf(...)`), sem artefato declarativo.
- **Opção C** — Estender a sintaxe de placeholder no próprio `application.yml` (ex.: `${PORT:int:8080}`), sem arquivo ou API separada.
- **Opção D** — Status quo: manter só verificação de presença.

## Decision Outcome

Opção escolhida: **"Opção A — Contrato declarativo explícito + API fluente de validação"**, porque é a única que cobre os três gaps de uma vez, mantém paridade Spring/não-Spring e produz um artefato versionável que habilita o roadmap (docs e CI), sem violar a restrição de zero dependências.

O contrato é sempre **opcional e aditivo**: sem ele, o comportamento atual (presença) permanece idêntico. Quando presente, ele *enriquece* — nunca substitui — o que já é inferido do ambiente.

### Forma concreta

- Um modelo de contrato no `env-governance-java`: `EnvContract` / `VarSpec` (nome, `required`, `type`, `allowedValues`, `pattern`, `description`, `sensitive`, condições).
- **Dois modos de declaração**, mapeando para o mesmo modelo:
  - **Programático** (não-Spring e testes): API fluente no builder do `GovernanceContext`
    (`.require("PORT").asPort()`, `.require("MODE").oneOf("a","b")`, `.requireIf("AUTH=approle", "ROLE_ID","SECRET_ID")`).
  - **Declarativo** (Spring e apps que preferem arquivo): `env-governance.yml` carregado no classpath, traduzido para o mesmo `EnvContract`.
- Um **motor de validação** compartilhado (`ValueValidator`) com validadores embutidos (`int`, `port`, `url`, `boolean`, `non-empty`, `oneOf`, `regex`, `min`/`max`) e extensível.
- Requisitos condicionais expressos como predicados sobre o mapa de ambiente já montado (reaproveita a lógica hoje embutida no Vault, que passa a consumir o mecanismo geral).
- Novo tipo de gap `[INVALID]` (valor presente mas fora do contrato), integrado ao reporter e ao endpoint Actuator ao lado dos gaps existentes.

### Positive Consequences

- Governança passa a cobrir **conteúdo**, não só presença — falhas de configuração param no startup em vez de em runtime.
- O `env-governance.yml` vira **fonte de verdade versionável**, base direta para geração de docs/`.env.example` e para enforcement em CI (decisões futuras).
- A lógica condicional do Vault deixa de ser especial: vira um consumidor do mecanismo geral, reduzindo código duplicado.
- Núcleo continua zero-dep; validação é implementada com JDK puro.

### Negative Consequences

- **Risco de duas fontes de verdade** — placeholders no `application.yml` e o `env-governance.yml` podem divergir. Mitigação: o contrato *anota* variáveis já descobertas por chave/placeholder; conflitos viram um gap reportado, não silêncio.
- **Superfície de API maior** — novos tipos públicos (`EnvContract`, `VarSpec`, `ValueValidator`) aumentam o custo de manutenção e o compromisso de compatibilidade.
- **Atrito de adoção** — extrair o valor pleno exige que a equipe escreva o contrato; sem isso, ganha-se pouco além do atual.
- **Custo de manter o contrato em dia** com a evolução da aplicação; um contrato desatualizado gera ruído de falso-positivo.

## Pros and Cons of the Options

### Opção A — Contrato declarativo + API fluente ✅ Escolhida

- ✅ Cobre os três gaps (valor, condicional, contrato explícito) num modelo único.
- ✅ Paridade Spring/não-Spring: um modelo, duas formas de declarar.
- ✅ Produz artefato versionável que habilita docs e enforcement em CI.
- ✅ Generaliza a lógica condicional hoje presa ao Vault.
- ❌ Maior superfície de API e risco de divergência entre YAML de config e contrato.
- ❌ Exige esforço de adoção para render o valor total.

### Opção B — Só estender a API fluente

- ✅ Menor esforço; encaixa no `GovernanceContext` sem novo formato.
- ✅ Mantém zero-dep trivialmente.
- ❌ Não gera artefato declarativo — sem base para docs nem CI.
- ❌ Cobre mal o Spring Boot, onde o contrato é inferido e não escrito à mão.

### Opção C — Estender a sintaxe de placeholder (`${PORT:int:8080}`)

- ✅ Fonte única de verdade (tudo no `application.yml`), zero arquivo novo.
- ❌ Expressividade limitada: difícil representar condicionais e enums complexos.
- ❌ Inutilizável fora do Spring (não há `application.yml` em Java puro/Quarkus/Micronaut).
- ❌ Sintaxe não-padrão que confunde quem lê o YAML sem conhecer o lib.

### Opção D — Status quo

- ✅ Custo zero; nenhuma nova superfície.
- ❌ Mantém as três lacunas; governança segue restrita a presença.

## Links

- Análise de gaps que originou esta decisão (sessão de 2026-07-14).
- Depende desta decisão (a serem registradas): enforcement em build/CI e geração automática de `.env.example`/docs.
- Contexto de arquitetura: `README.md` (SPI `EnvVarSource`, pipeline de `EnvironmentPostProcessor`).
- Memórias de projeto: `project_pluggable_env_sources.md`, `project_java_module_refactor.md`.
