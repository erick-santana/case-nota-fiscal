# Evidência de performance

Reexecutável com `k6 run k6/scenario-*.js` (variável `BASE_URL`, padrão `http://localhost:8080`).

Este arquivo tem duas execuções dos mesmos quatro cenários, contra duas versões diferentes do serviço:

1. **SPEC-03** — núcleo hexagonal com as quatro notificações síncronas (`Estoque`/`Registro`/`Entrega`/`Financeiro`) paralelizadas via `CompletableFuture` sobre virtual threads, cada uma com sua latência simulada (`Thread.sleep`).
2. **SPEC-07** — as quatro chamadas foram **removidas**; o serviço grava um único registro no outbox transacional (DynamoDB) e responde, sem aguardar nenhuma das quatro áreas (ver [`SOLUCAO-PROPOSTA.md`](../SOLUCAO-PROPOSTA.md#4-arquitetura-de-quatro-chamadas-com-new-a-outbox-transacional)).

A comparação entre as duas execuções está na seção 3, ao final deste arquivo.

## 1. Execução SPEC-03 — quatro notificações síncronas paralelizadas

k6 v2.2.0, app local (`./mvnw spring-boot:run`), Java 21 (Corretto 21.0.12.1), 2026-09-21.

### Cenário A — 1 item vs. 6 itens (REQ-3.2)

`k6 run scenario-a-item-count.js`

| Métrica | 1 item | 6 itens |
|---|---|---|
| avg | 507.56ms | 505.64ms |
| p90 | 510.34ms | 506.89ms |
| p95 | 512.29ms | 507.62ms |

p95 comparável entre 1 e 6 itens — nenhum salto para a ordem de segundos que o `+5s` condicional produzia (~5,35s a partir de 6 itens no código antigo).

### Cenário B — execuções sucessivas sob carga constante (REQ-3.1)

`k6 run scenario-b-successive-runs.js` — 300 iterações, 10 VUs, comparando primeiro e último terço.

| Métrica | 1º terço | último terço |
|---|---|---|
| avg | 504.71ms | 503.91ms |
| p95 | 508.63ms | 504.94ms |

Sem tendência de crescimento entre o início e o fim da execução — o antigo `CalculadoraAliquotaProduto.itemNotaFiscalList` estático teria degradado ao longo do tempo.

### Cenário C — bloco de notificações (REQ-3.3)

`k6 run scenario-c-notification-block.js` — 30 iterações, 1 VU.

| Métrica | Valor |
|---|---|
| avg | 503.96ms |
| p95 | 504.57ms |

Tempo total na ordem da chamada mais lenta (Registro, ~500ms), não da soma sequencial das quatro (380+500+150+200+250 ≈ 1480ms).

### Cenário D — carga concorrente (paralelização sob concorrência)

`k6 run scenario-d-concurrent-load.js` — rampa de 1 a 50 VUs.

| Métrica | Valor |
|---|---|
| avg | 502.99ms |
| p95 | 503.83ms |
| falhas | 0,00% (2100/2100 com sucesso) |

Latência estável mesmo sob 50 VUs concorrentes — o executor de virtual threads não enfileira como um pool de plataforma fixo enfileiraria.

## 2. Execução SPEC-07 — outbox transacional (DynamoDB Local)

k6 v2.2.0, mesmos quatro scripts, sem nenhuma edição. App local (`./mvnw spring-boot:run`), Java 21 (Corretto 21.0.12.1), `DYNAMODB_ENDPOINT_OVERRIDE=http://localhost:8000` apontando para um contêiner `amazon/dynamodb-local` (`-inMemory -sharedDb`), 2026-09-21. As quatro notificações síncronas (e a espera pela mais lenta delas) não existem mais no caminho da requisição — o que os cenários agora medem é o tempo de calcular a nota fiscal + gravar um `PutItem` no outbox.

### Cenário A — 1 item vs. 6 itens (REQ-3.2)

`k6 run scenario-a-item-count.js`

| Métrica | 1 item | 6 itens |
|---|---|---|
| avg | 8.22ms | 6.98ms |
| p90 | 10.64ms | 8.54ms |
| p95 | 13.74ms | 9.08ms |

p95 continua comparável entre 1 e 6 itens (sem penalidade por quantidade de itens) — e, sem as quatro notificações a aguardar, o piso de latência deixou de ser ~500ms para ficar na casa de milissegundos de um `PutItem` local.

### Cenário B — execuções sucessivas sob carga constante (REQ-3.1)

`k6 run scenario-b-successive-runs.js` — 300 iterações, 10 VUs, comparando primeiro e último terço.

| Métrica | 1º terço | último terço |
|---|---|---|
| avg | 12.46ms | 9.84ms |
| p95 | 19.29ms | 12.9ms |

Sem tendência de crescimento entre início e fim — a leve queda do 1º para o último terço é ruído de JIT/warm-up do processo, não degradação.

### Cenário C — escrita no outbox (ex-"bloco de notificações", REQ-3.3/D-07)

`k6 run scenario-c-notification-block.js` — 30 iterações, 1 VU.

| Métrica | Valor |
|---|---|
| avg | 5.13ms |
| p95 | 6.67ms |

Já não há "bloco de notificações" a medir — as quatro chamadas foram removidas (D-07). O número mede o novo caminho crítico: cálculo da nota + um `PutItem` síncrono no outbox, sem nenhuma dependência de Estoque/Registro/Entrega/Financeiro.

### Cenário D — carga concorrente (paralelização sob concorrência)

`k6 run scenario-d-concurrent-load.js` — rampa de 1 a 50 VUs.

| Métrica | Valor |
|---|---|
| avg | 26.05ms |
| p95 | 48.51ms |
| falhas | 0,00% (39416/39416 com sucesso) |

Zero falhas sob 50 VUs concorrentes, como antes. A latência sobe um pouco com a concorrência (p95 de ~9ms com 1 VU parado para ~48ms sob rampa de 50 VUs) porque agora o gargalo é o único processo do DynamoDB Local recebendo `PutItem` concorrente — algo que não existia quando a "integração" era apenas `Thread.sleep` em memória, paralelizado por virtual threads sem contenção de recurso externo nenhum. Mesmo assim, ~48ms de p95 sob 50 VUs é uma ordem de grandeza abaixo dos ~504ms fixos da versão anterior (ver comparação abaixo) — e, em produção, esse ponto de contenção passa a ser o DynamoDB real (escala horizontalmente, sem o teto de um único contêiner local).

## 3. Comparação SPEC-03 → SPEC-07

Mesmos quatro scripts k6, sem edição, contra as duas versões do serviço. Ganho de p95 (arredondado):

| Cenário | Métrica | SPEC-03 (4 chamadas paralelas) | SPEC-07 (outbox) | Redução de p95 |
|---|---|---|---|---|
| A — 1 item | p95 | 512.29ms | 13.74ms | **~37×** |
| A — 6 itens | p95 | 507.62ms | 9.08ms | **~56×** |
| B — 1º terço | p95 | 508.63ms | 19.29ms | **~26×** |
| B — último terço | p95 | 504.94ms | 12.90ms | **~39×** |
| C — bloco de notificações / outbox | p95 | 504.57ms | 6.67ms | **~76×** |
| D — 50 VUs concorrentes | p95 | 503.83ms | 48.51ms | **~10×** |
| D — taxa de falha | — | 0,00% (2100/2100) | 0,00% (39416/39416) | inalterada |

Leitura do resultado:

- **O ganho não veio de "otimizar" a latência simulada** (P3 continua respeitado — nenhum `Thread.sleep` foi removido; os `Thread.sleep` das quatro integrações não existem mais porque as próprias chamadas foram removidas do caminho síncrono, não porque foram aceleradas). O ganho vem de uma mudança estrutural: a resposta HTTP não depende mais da confirmação de Estoque/Registro/Entrega/Financeiro — só do cálculo (in-process, determinístico) e de uma escrita no outbox.
- **O caso D é o único onde o ganho relativo é menor** (~10× em vez de ~26–76×) porque introduz um recurso externo real (ainda que local) no caminho crítico pela primeira vez: DynamoDB Local, rodando como processo único, vira o gargalo sob 50 VUs concorrentes — diferente das quatro chamadas antigas, que eram `Thread.sleep` em memória sem contenção de recurso nenhum. Isso é esperado e coerente com o desenho: em produção, esse papel é do DynamoDB gerenciado (particionado, escala horizontal), não de um contêiner único de desenvolvimento.
- **A latência de referência da versão anterior era artificialmente alta e constante (~500ms) por construção** — é a soma/máximo de `Thread.sleep`s fixos, não uma medida de custo computacional real. A comparação, portanto, não deve ser lida como "56× mais rápido em produção", e sim como confirmação de que o novo desenho eliminou a dependência síncrona que limitava a resposta ao tempo da integração mais lenta.
