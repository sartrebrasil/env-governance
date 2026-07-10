# env-governance

Spring Boot starter que detecta gaps de configuração de variáveis de ambiente no startup — propriedades sem valor, placeholders apontando para variáveis inexistentes no SO/container, e variáveis do ambiente que nenhuma propriedade utiliza.

[![Spring Boot 3.x](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F)](https://spring.io/projects/spring-boot)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00)](https://openjdk.org/projects/jdk/21/)

---

## Índice

- [O problema que resolve](#o-problema-que-resolve)
- [Instalação](#instalação)
- [Como funciona](#como-funciona)
- [Configuração](#configuração)
- [Interpretando os logs](#interpretando-os-logs)
- [Endpoint Actuator](#endpoint-actuator)
- [Referência de gaps](#referência-de-gaps)

---

## O problema que resolve

Em containers e ambientes cloud, propriedades do Spring Boot podem ser sobrescritas por variáveis de ambiente a qualquer momento — mesmo sem `${PLACEHOLDER}` explícito no YAML. Descobrir que uma variável está errada, faltando ou sendo ignorada costuma acontecer em produção, quando o custo é alto.

O **env-governance** roda no startup, cruza as propriedades dos arquivos de configuração com o ambiente do SO/container e loga exatamente o que está mal configurado — sem necessidade de código adicional na aplicação.

---

## Instalação

Adicione o starter ao `pom.xml`. Nenhuma outra configuração é obrigatória — a auto-configuração cuida de tudo.

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>env-governance-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Ao subir a aplicação, o diagnóstico aparece automaticamente nos logs.

> **Módulos incluídos pelo starter:** `env-governance-core` (scanners e registries) + `env-governance-autoconfigure` (reporter e endpoint Actuator, se `spring-boot-actuator` estiver no classpath).

---

## Como funciona

### Pipeline de inicialização

```
1. ConfigDataEnvironmentPostProcessor (Spring Boot, +10)
   └─ Carrega application*.yml/properties nas PropertySources

2. DeclaredVarsScanner (+15)
   └─ Para cada propriedade nas PropertySources do classpath:
       ├─ Registra var derivada pela CHAVE (relaxed binding)
       └─ Escaneia o VALOR em busca de ${PLACEHOLDER} (nome explícito)

3. UsageTrackingEnvironmentPostProcessor (LOWEST_PRECEDENCE)
   └─ Envolve as PropertySources para rastrear chaves lidas em runtime

4. EnvVarUsageReporter (ApplicationReadyEvent)
   └─ Cruza DeclaredVarsRegistry × System.getenv()
       ├─ INFO  → gaps acionáveis
       └─ DEBUG → diagnóstico completo
```

### Mecanismo 1 — Relaxed Binding (chave)

Toda chave de propriedade é convertida para o nome de variável de ambiente equivalente do Spring Boot: pontos e hífens viram underscores, tudo maiúsculo.

```yaml
# application.yml
spring:
  application:
    name: sftp-bridge
```

→ registra a variável potencial **`SPRING_APPLICATION_NAME`**

Exemplos de conversão:

| Chave da propriedade | Variável de ambiente |
|---|---|
| `spring.application.name` | `SPRING_APPLICATION_NAME` |
| `server.ssl.key-store` | `SERVER_SSL_KEY_STORE` |
| `spring.datasource.hikari.max-pool-size` | `SPRING_DATASOURCE_HIKARI_MAX_POOL_SIZE` |

### Mecanismo 2 — Placeholder Explícito (valor)

Se o valor de uma propriedade contiver `${VAR_NAME}` ou `${VAR_NAME:default}`, o `VAR_NAME` é registrado como variável explícita com seu nome próprio — que **pode diferir** do nome derivado pela chave.

```yaml
# application.yml
bridge:
  poller:
    max-messages-per-poll: ${BRIDGE_MAX_MESSAGES_PER_POLL:100}
```

→ registra **dois** nomes distintos:
- `BRIDGE_POLLER_MAX_MESSAGES_PER_POLL` — derivado da chave (relaxed binding)
- `BRIDGE_MAX_MESSAGES_PER_POLL` — nome explícito do placeholder

### Prioridade de registro: explicit ganha

Quando o nome derivado pela chave coincide com o nome do placeholder (ex: `server.port: ${SERVER_PORT:8080}`), o placeholder vence e é registrado como `explicit=true`, pois carrega informação mais precisa sobre a obrigatoriedade da variável.

---

## Configuração

Todas as propriedades têm defaults razoáveis. Adicione ao `application.yml` apenas o que precisar sobrescrever.

```yaml
env:
  governance:
    enabled: true              # desliga toda a lib quando false
    report-on-ready: true      # loga diagnóstico no ApplicationReadyEvent
    report-orphan-vars: true   # inclui seção [UNUSED] no relatório
    orphan-report-limit: 50    # limita vars não utilizadas exibidas
```

| Propriedade | Default | Descrição |
|---|---|---|
| `env.governance.enabled` | `true` | Desliga toda a lib. Útil em perfis de teste. |
| `env.governance.report-on-ready` | `true` | Controla se o diagnóstico é produzido no `ApplicationReadyEvent`. Desabilite se quiser apenas o endpoint Actuator. |
| `env.governance.report-orphan-vars` | `true` | Inclui a seção `[UNUSED]`. Desabilite em ambientes com muitas vars de infraestrutura para reduzir ruído. |
| `env.governance.orphan-report-limit` | `50` | Limita quantas vars não utilizadas são exibidas. Evita spam quando o container tem centenas de vars de sistema. |

### Desabilitando por perfil

```yaml
# application-test.yml
env:
  governance:
    enabled: false  # silencia tudo nos testes de integração
```

---

## Interpretando os logs

### Nível INFO — gaps acionáveis

Aparece sempre que há algo a reportar. Usa `log.error` para casos críticos, `log.warn` para alertas. Se tudo estiver OK, uma única linha aparece.

**Com gaps:**

```
INFO  ===== ENV GOVERNANCE: GAPS DE CONFIGURAÇÃO =====

ERROR [REQUIRED] Variáveis obrigatórias ausentes no SO/container (1):
ERROR   DB_PASSWORD                                    →  spring.datasource.password  [application.yml]

WARN  [FALLBACK] Variáveis ausentes no SO/container, usando valor padrão (2):
WARN    BRIDGE_MAX_MESSAGES_PER_POLL                   →  bridge.poller.max-messages-per-poll  [application.yml]
WARN    REDIS_PORT                                     →  spring.data.redis.port  [application-redis.yml]

WARN  [NO VALUE] Propriedades sem valor no YAML e sem variável de ambiente (1):
WARN    SPRING_DATASOURCE_URL                          →  spring.datasource.url  [application.yml]

WARN  [UNUSED] Variáveis do SO/container não utilizadas pela aplicação (148):
WARN    COMPUTERNAME
WARN    HOME
WARN    JAVA_HOME
WARN    PATH
WARN    ...

INFO  =================================================
```

**Sem gaps:**

```
INFO  [ENV GOVERNANCE] Sem gaps de configuração detectados.
```

### Nível DEBUG — diagnóstico completo

Ative adicionando ao `application.yml`:

```yaml
logging:
  level:
    com.example.envgovernance: DEBUG
```

```
DEBUG ===== ENV GOVERNANCE: DIAGNÓSTICO COMPLETO (startup) =====

DEBUG [APPLICATION] Variáveis de ambiente potenciais (6 var(s)):
DEBUG   BRIDGE_MAX_MESSAGES_PER_POLL       [${} optional]  →  bridge.poller.max-messages-per-poll  [application.yml]
DEBUG   BRIDGE_POLLER_MAX_MESSAGES_PER_POLL [key]           →  bridge.poller.max-messages-per-poll  [application.yml]
DEBUG   DB_PASSWORD                        [${} required]  →  spring.datasource.password           [application.yml]
DEBUG   SERVER_PORT                        [${} optional]  →  server.port                          [application.yml]
DEBUG   SPRING_APPLICATION_NAME            [key]           →  spring.application.name              [application.yml]
DEBUG   SPRING_DATASOURCE_URL              [key/no-value]  →  spring.datasource.url                [application.yml]

DEBUG [ENVIRONMENT] Variáveis definidas no SO/container (152 var(s)):
DEBUG   HOME
DEBUG   JAVA_HOME
DEBUG   SERVER_PORT
DEBUG   SPRING_APPLICATION_NAME
DEBUG   ...

DEBUG [ACTIVE] Substituições ativas: SO sobrescrevendo propriedades da aplicação (2 var(s)):
DEBUG   SERVER_PORT              →  server.port              [application.yml]
DEBUG   SPRING_APPLICATION_NAME  →  spring.application.name  [application.yml]

DEBUG [UNUSED] Variáveis do SO não utilizadas pela aplicação (150 var(s)):
DEBUG   HOME
DEBUG   JAVA_HOME
DEBUG   PATH
DEBUG   ...

DEBUG ==========================================================
```

### Marcadores na seção `[APPLICATION]`

| Marcador | Origem | Significado |
|---|---|---|
| `[key]` | Chave da propriedade | Var derivada por relaxed binding. O YAML tem valor — a env var é opcional (apenas sobrescreve se definida). |
| `[key/no-value]` | Chave da propriedade | Var derivada, mas a propriedade está sem valor no YAML. Aparece como `[NO VALUE]` no INFO se a env var também não estiver no SO. |
| `[${} optional]` | Placeholder no valor | Var explícita com default: `${VAR:fallback}`. Se ausente no SO, usa o fallback declarado. |
| `[${} required]` | Placeholder no valor | Var explícita sem default: `${VAR}`. Se ausente no SO, aparece como `[REQUIRED]` no INFO. |

---

## Endpoint Actuator

Quando `spring-boot-actuator` está no classpath, o endpoint é registrado automaticamente. Basta expô-lo:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: env-governance
```

Acesse via `GET /actuator/env-governance`.

### Estrutura da resposta

```json
{
  "gaps": {
    "required": [
      {
        "name": "DB_PASSWORD",
        "propertyKey": "spring.datasource.password",
        "sourceFile": "application.yml"
      }
    ],
    "fallback": [
      {
        "name": "BRIDGE_MAX_MESSAGES_PER_POLL",
        "propertyKey": "bridge.poller.max-messages-per-poll",
        "sourceFile": "application.yml"
      }
    ],
    "noValue": []
  },
  "unused": ["HOME", "JAVA_HOME", "PATH"],
  "activeOverrides": [
    {
      "envVar": "SERVER_PORT",
      "propertyKey": "server.port",
      "sourceFile": "application.yml"
    }
  ],
  "applicationVars": [
    {
      "name": "SERVER_PORT",
      "propertyKey": "server.port",
      "sourceFile": "application.yml",
      "origin": "placeholder-optional"
    }
  ]
}
```

| Campo | Conteúdo |
|---|---|
| `gaps.required` | Placeholders `${VAR}` sem default cujo `VAR` não está no SO. |
| `gaps.fallback` | Placeholders `${VAR:default}` cujo `VAR` não está no SO (usando fallback). |
| `gaps.noValue` | Propriedades com valor null/vazio no YAML e sem env var correspondente. |
| `unused` | Vars do SO sem correspondência em nenhuma propriedade da aplicação. |
| `activeOverrides` | Vars do SO que estão ativamente sobrescrevendo propriedades. |
| `applicationVars` | Lista completa com `origin`: `key-derived`, `key-derived/no-value`, `placeholder-optional`, `placeholder-required`. |

---

## Referência de gaps

### `[REQUIRED]` — Crítico (`log.error`)

**O que é:** a propriedade usa `${VAR}` *sem valor de fallback* e `VAR` não está definida no SO/container.

O Spring Boot tentará resolver o placeholder ao injetar a propriedade. Se nenhum bean ler essa propriedade no startup, a aplicação pode subir — mas falhará silenciosamente em runtime ao tentar usá-la.

**O que fazer:** defina a variável no container/SO antes de iniciar a aplicação.

---

### `[FALLBACK]` — Atenção (`log.warn`)

**O que é:** a propriedade usa `${VAR:valor_padrão}` e `VAR` não está no SO. O valor padrão declarado no YAML está sendo utilizado.

Pode ser intencional (ex: porta padrão em ambiente local) ou um esquecimento em produção. Revise se o valor padrão é adequado para o ambiente atual.

**O que fazer:** confirme se o fallback é aceitável. Defina a variável se precisar de um valor diferente.

---

### `[NO VALUE]` — Atenção (`log.warn`)

**O que é:** a propriedade existe no YAML mas está sem valor (null ou vazio), e não há variável de ambiente correspondente definida no SO.

A propriedade terá valor `null` em runtime. Componentes Spring que dependam dela podem falhar ao tentar converter ou usar o valor.

**O que fazer:** adicione um valor concreto no YAML ou defina a variável `NOME_EM_UPPER_CASE` no SO.

---

### `[UNUSED]` — Informativo (`log.warn`)

**O que é:** a variável está definida no SO/container mas não corresponde a nenhuma propriedade conhecida da aplicação — nem por chave (relaxed binding) nem por placeholder explícito.

Possíveis causas: typo no nome da variável, propriedade removida do YAML, variável de outro serviço no mesmo container, variável de infraestrutura não relacionada à aplicação.

**O que fazer:** verifique se o nome está correto, se a propriedade ainda existe, ou se a variável pode ser removida do ambiente.

---

### Cenário: mesma propriedade, dois nomes de variável

O caso mais comum de confusão: um placeholder cujo nome difere do que o relaxed binding geraria para a chave. Ambos são registrados e qualquer um funciona para sobrescrever o valor.

```yaml
# application.yml
bridge:
  poller:
    max-messages-per-poll: ${BRIDGE_MAX_MESSAGES_PER_POLL:100}
```

```bash
# Opção 1 — sobrescreve via relaxed binding (nome derivado da chave)
BRIDGE_POLLER_MAX_MESSAGES_PER_POLL=200

# Opção 2 — sobrescreve via placeholder (nome explícito no YAML)
BRIDGE_MAX_MESSAGES_PER_POLL=200
```

> Quando `BRIDGE_POLLER_MAX_MESSAGES_PER_POLL` está definida no SO, ela sobrescreve a propriedade inteira via relaxed binding, antes mesmo do Spring avaliar o placeholder interno. Nesse caso, `[FALLBACK]` para `BRIDGE_MAX_MESSAGES_PER_POLL` no INFO é esperado e pode ser ignorado.
