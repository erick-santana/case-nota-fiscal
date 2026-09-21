# SDD — Spec-Driven Development

A especificação é a fonte de verdade desta entrega. O código é a **implementação** de uma spec, não o contrário: nada é escrito sem estar especificado, e nada fica especificado sem critério de verificação executável.

Não há histórias, épicos, pontos ou ritos de backlog aqui. Há seis especificações, uma constituição que todas herdam, e um portão de verificação por spec.

## Estrutura

```
docs/sdd/
├── CONSTITUICAO.md     princípios invioláveis, decisões transversais D-01…D-07, restrições
└── specs/
    ├── SPEC-01-stack-e-base.md
    ├── SPEC-02-rede-de-testes.md
    ├── SPEC-03-nucleo-alvo.md
    ├── SPEC-04-seguranca-e-validacao.md
    ├── SPEC-05-observabilidade-e-configuracao.md
    ├── SPEC-06-entrega-e-arquitetura.md
    └── SPEC-07-outbox-dynamodb.md
```

Contexto de negócio em [`../VISAO-DE-NEGOCIO.md`](../VISAO-DE-NEGOCIO.md). Arquitetura produtiva em [`../rfc/RFC-0001`](../rfc/RFC-0001-arquitetura-produtiva-aws.md) e [`../adr/ADR-0001`](../adr/ADR-0001-notificacoes-assincronas-saga-orquestrada.md).

## Contrato de execução

Vale para qualquer executor — pessoa ou agente:

1. **Ler [`CONSTITUICAO.md`](./CONSTITUICAO.md) antes da spec.** Os princípios P1–P5 e as decisões D-01…D-06 não são repetidos dentro das specs; são referenciados por ID.
2. **Executar uma spec por vez, na ordem abaixo.** Cada uma declara de quem depende e o que habilita.
3. **Não tocar no que está em "Fora de escopo".** Se a implementação exigir algo fora do escopo declarado, o caminho é alterar a spec — com justificativa — e só então o código.
4. **O portão de verificação é literal.** Os comandos da seção "Verificação" precisam passar. "Deveria funcionar" não fecha spec.
5. **Divergência entre spec e código é defeito da spec até prova em contrário.** Ao encontrar uma, corrigir o documento no mesmo commit em que o código diverge dele.

Cada spec tem o mesmo esqueleto: **Objetivo → Contexto verificado → Requisitos (`REQ-x.y`, normativos e testáveis) → Desenho → Plano de execução → Verificação → Fora de escopo**.

## Ordem de execução

| # | Spec | Por que nesta posição | Portão para seguir |
|---|---|---|---|
| 1 | [SPEC-01 — Stack e base](./specs/SPEC-01-stack-e-base.md) | Todo código subsequente nasce na stack final, sem retrabalho de versão. Há um impedimento técnico duro: Lombok 1.18.22 (BOM do parent 2.6.2) não compila sob JDK 21 | `./mvnw clean verify` verde em Java 21 |
| 2 | [SPEC-02 — Rede de testes](./specs/SPEC-02-rede-de-testes.md) | A rede precisa existir e estar verde **antes** da reescrita, senão não prova equivalência (P5) | Suíte de caracterização e contrato verde — é o que autoriza SPEC-03 |
| 3 | [SPEC-03 — Núcleo alvo](./specs/SPEC-03-nucleo-alvo.md) | Reescrita estrutural. SPEC-04 e SPEC-05 dependem das portas e do adaptador web dela | Suíte de SPEC-02 passa sem edição, exceto o caso de D-03 |
| 4 | [SPEC-04 — Segurança e validação](./specs/SPEC-04-seguranca-e-validacao.md) | Paralela a SPEC-05: tocam camadas diferentes (entrada/segurança vs. instrumentação/config) e não colidem | Testes de fail-closed e de validação verdes |
| 5 | [SPEC-05 — Observabilidade e configuração](./specs/SPEC-05-observabilidade-e-configuracao.md) | Idem | `docker compose up` sobe app + Prometheus + Grafana |
| 6 | [SPEC-06 — Entrega e arquitetura](./specs/SPEC-06-entrega-e-arquitetura.md) | O CI valida o conjunto; o mapeamento porta→AWS do RFC exige os nomes reais de SPEC-03 | Pipeline verde em PR e em push para `main` |
| 7 | [SPEC-07 — Outbox DynamoDB](./specs/SPEC-07-outbox-dynamodb.md) | Entrega adicional, pós-SPEC-06: primeiro passo em código do caminho de migração incremental do RFC-0001/ADR-0001 | `./mvnw clean verify` verde, adapter real testado via Testcontainers |

```
SPEC-01 ──> SPEC-02 ──> SPEC-03 ──┬──> SPEC-04 ──┐
                                  └──> SPEC-05 ──┴──> SPEC-06 ──> SPEC-07
```

## Mapa de decisões × specs

| Decisão | Onde é implementada | Onde é vigiada |
|---|---|---|
| D-01 virtual threads | SPEC-03 | cenários k6 de SPEC-03 |
| D-02 falha parcial agregada | SPEC-03 | teste de caso de uso com portas mockadas |
| D-03 `OUTROS` → `422` | SPEC-03 | caso de caracterização de SPEC-02 (única edição autorizada) |
| D-04 `valor_total_itens` validado | SPEC-04 | teste nos dois sentidos (divergente → `400`, dentro da tolerância → `200`) |
| D-05 tributo ignora `quantidade` | preservada em SPEC-03 | travada por teste em SPEC-02 |
| D-06 campos descartados | preservada (nenhuma ação) | teste de contrato em quatro níveis, SPEC-02 |
| D-07 outbox DynamoDB substitui D-01/D-02 | SPEC-07 | `DynamoDbNotaFiscalProcessamentoAdapterTest` (Testcontainers) e `GerarNotaFiscalServiceTest` |

## Histórico

Estas specs substituem duas formulações anteriores: um backlog de 10 histórias (`US-01`…`US-10`) com SDDs pareados, e uma consolidação intermediária em seis épicos com `HISTORIAS.md`/`TASKS.md`/`stories/`. A análise que motivou a consolidação — incluindo as regressões que o modelo incremental produzia, hoje eliminadas pelo princípio P1 — não está preservada como documento separado neste repositório; o resultado dessa análise está incorporado diretamente nestas specs e no princípio P1.

Rastreabilidade a partir da numeração antiga:

| Formulação US | Épico E | Spec atual |
|---|---|---|
| US-04 Modernização da stack | E1 | **SPEC-01** |
| US-02 Rede de testes de regressão | E2 | **SPEC-02** |
| US-01 Isolamento entre requisições | E3 | **SPEC-03** |
| US-03 Estabilidade de performance | E3 | **SPEC-03** |
| US-05 Arquitetura hexagonal | E3 | **SPEC-03** |
| US-07 Segurança e validação | E4 | **SPEC-04** |
| US-06 Observabilidade | E5 | **SPEC-05** |
| US-08 Execução local e AWS | E5 | **SPEC-05** |
| US-09 Visão arquitetural | E6 | **SPEC-06** |
| US-10 Ciclo de entrega | E6 | **SPEC-06** |
