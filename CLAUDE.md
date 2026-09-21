# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

This is a technical challenge ("Desafio Técnico — Nota Fiscal", see `README.md`, in Portuguese) built around a deliberately flawed Spring Boot service that generates a `NotaFiscal` (invoice) from a `Pedido` (order), calculating tax (`aliquota`) per item and freight cost. The task is to fix known functional/performance bugs and evolve the code's architecture, testing, and delivery practices — not to build new product features. Read `README.md` in full before making changes; it defines constraints that override typical "just fix it" instincts:

- **The input JSON payload contract must not change** (`Pedido` fields/shape are fixed).
- **Simulated latencies in downstream integrations are intentional** and represent real external calls — do not delete `Thread.sleep` calls as a shortcut to "improve performance"; fix genuine bottlenecks instead (e.g. sequential vs. concurrent calls, the exponential/O(n²)-shaped delay below).
- Target runtime is **Java 21** and the latest stable Spring Boot compatible with it (currently Spring Boot 2.6.2 / Java 11 — this is a required upgrade, not just an option).
- An architectural proposal (deployment context: API Gateway, auth, AWS infra, resilience, observability, etc.) is expected as part of the deliverable, separate from the code changes.

## Build & test

Use the Maven wrapper (no local Maven install required):

```bash
./mvnw compile                 # compile
./mvnw test                    # run all tests
./mvnw test -Dtest=GeradorNotaFiscalServiceImplTest        # run a single test class
./mvnw test -Dtest=GeradorNotaFiscalServiceImplTest#shouldGenerateNotaFiscalForTipoPessoaFisicaWithValorTotalItensLessThan500  # single test method
./mvnw spring-boot:run          # run the app locally (default port 8080)
./mvnw clean package            # build the jar
```

Tests live under `src/test/java/br/com/itau/calculadoratributos/` (note: different package path than `main`, which is under `br.com.itau.geradornotafiscal`). This mismatch is pre-existing.

## Architecture

Single end-to-end flow, no persistence layer, no database:

```
GeradorNFController (POST /api/pedido/gerarNotaFiscal)
  -> GeradorNotaFiscalService / GeradorNotaFiscalServiceImpl   (tax + freight calculation, orchestration)
       -> CalculadoraAliquotaProduto                            (applies a tax rate to each item)
       -> EstoqueService, RegistroService, EntregaService, FinanceiroService  (downstream "integrations", each a plain `new` instantiation, each simulates latency via Thread.sleep)
            -> EntregaIntegrationPort                           (simulates an external delivery-scheduling API)
```

Key things to know before changing this code:

- **Tax rate (`aliquota`) rules** live inline in `GeradorNotaFiscalServiceImpl.gerarNotaFiscal`, branching on `Destinatario.tipoPessoa` (FISICA vs JURIDICA) and, for JURIDICA, on `RegimeTributacaoPJ` (SIMPLES_NACIONAL / LUCRO_REAL / LUCRO_PRESUMIDO), each with its own bracket thresholds against `pedido.getValorTotalItens()`. This is the "many calculation rules concentrated in one class" problem the README calls out — the four branches are near-duplicates of each other differing only in bracket values.
- **`CalculadoraAliquotaProduto.itemNotaFiscalList` is a `static` field** (`service/CalculadoraAliquotaProduto.java`). It accumulates `ItemNotaFiscal` entries across every call/request instead of being scoped per invocation — this is the root cause of the README's reported bug where item lists and totals from previous requests "leak" into later responses, and it's also not thread-safe under concurrent requests.
- **Freight (`valorFrete`) markup** is a second, separate branch keyed on `Regiao`, derived from whichever `Endereco` in `Destinatario.enderecos` has `Finalidade.ENTREGA` or `Finalidade.COBRANCA_ENTREGA`.
- **Downstream service calls are synchronous and sequential** (`EstoqueService`, `RegistroService`, `EntregaService`, `FinanceiroService`, each `new`'d directly in the service impl rather than injected as Spring beans), each with its own fixed `Thread.sleep`. `EntregaIntegrationPort.criarAgendamentoEntrega` additionally sleeps an extra 5s when `notaFiscal.getItens().size() > 5` — this is the source of the README's ">6 items = slow" performance complaint, deliberately left as a puzzle to find via `EntregaIntegrationPort` rather than documented up front.
- Response DTO (`NotaFiscal`) and request DTO (`Pedido`) are Lombok `@Builder`/`@Getter`/`@Setter` POJOs with explicit `@JsonProperty` snake_case mappings — the JSON contract (field names) is defined by these annotations and must be preserved per the README constraint.
- Sample request payloads for manual testing are in `src/main/resources/paylods/` (note: `paylods`, not `payloads` — pre-existing typo) — `teste-pf.json` (pessoa física) and `teste-pj-simples.json` (pessoa jurídica, Simples Nacional).
