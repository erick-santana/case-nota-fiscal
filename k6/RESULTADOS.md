# Evidência de performance — SPEC-03

Execução real, k6 v2.2.0, app local (`./mvnw spring-boot:run`), Java 21 (Corretto 21.0.12.1), 2026-09-21.
Reexecutável com `k6 run k6/scenario-*.js` (variável `BASE_URL`, padrão `http://localhost:8080`).

## Cenário A — 1 item vs. 6 itens (REQ-3.2)

`k6 run scenario-a-item-count.js`

| Métrica | 1 item | 6 itens |
|---|---|---|
| avg | 507.56ms | 505.64ms |
| p90 | 510.34ms | 506.89ms |
| p95 | 512.29ms | 507.62ms |

p95 comparável entre 1 e 6 itens — nenhum salto para a ordem de segundos que o `+5s` condicional produzia (~5,35s a partir de 6 itens no código antigo).

## Cenário B — execuções sucessivas sob carga constante (REQ-3.1)

`k6 run scenario-b-successive-runs.js` — 300 iterações, 10 VUs, comparando primeiro e último terço.

| Métrica | 1º terço | último terço |
|---|---|---|
| avg | 504.71ms | 503.91ms |
| p95 | 508.63ms | 504.94ms |

Sem tendência de crescimento entre o início e o fim da execução — o antigo `CalculadoraAliquotaProduto.itemNotaFiscalList` estático teria degradado ao longo do tempo.

## Cenário C — bloco de notificações (REQ-3.3)

`k6 run scenario-c-notification-block.js` — 30 iterações, 1 VU.

| Métrica | Valor |
|---|---|
| avg | 503.96ms |
| p95 | 504.57ms |

Tempo total na ordem da chamada mais lenta (Registro, ~500ms), não da soma sequencial das quatro (380+500+150+200+250 ≈ 1480ms).

## Cenário D — carga concorrente (paralelização sob concorrência)

`k6 run scenario-d-concurrent-load.js` — rampa de 1 a 50 VUs.

| Métrica | Valor |
|---|---|
| avg | 502.99ms |
| p95 | 503.83ms |
| falhas | 0,00% (2100/2100 com sucesso) |

Latência estável mesmo sob 50 VUs concorrentes — o executor de virtual threads não enfileira como um pool de plataforma fixo enfileiraria.
