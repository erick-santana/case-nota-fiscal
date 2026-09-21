# Suíte E2E — Robot Framework

Três suítes:

- `tests/gerar_nota_fiscal.robot` — fumaça de ponta a ponta: gera uma nota fiscal real, via HTTP,
  para cada payload de exemplo em `src/main/resources/paylods/`. Só confirma que a aplicação sobe
  e responde de ponta a ponta.
- `tests/pedido_invalido.robot` — bad cases de ponta a ponta: variações mínimas do payload de
  entrada (`resources/payloads_invalidos/`), cada uma quebrando exatamente uma regra de validação
  de SPEC-04 (campo ausente/negativo, `itens` vazio ou acima do limite, D-04, regressão do NPE de
  `destinatario.enderecos`, `regime_tributacao` ausente em JURIDICA, JSON malformado), esperando
  `400` no serviço real.
- `tests/pedido_border_cases.robot` — o outro lado da mesma borda: variações mínimas do payload de
  entrada (`resources/payloads_validos/`) exatamente no limite (ou logo abaixo dele) de uma regra
  de validação — `itens` com 50 itens (o máximo aceito), valores no piso `0`/`1` de
  `@PositiveOrZero`/`@Positive`, divergência de `valor_total_itens` dentro da tolerância de D-04,
  `Endereco.complemento` ausente, `enderecos` vazio (não nulo) — esperando `200`.

Todas complementam a suíte de caracterização/validação em `src/test`, que cobre as mesmas regras
em memória (mais rápido, mais granular); aqui o objetivo é confirmar que o comportamento
observado bate com o esperado quando a aplicação está de pé de verdade.

## Executar

```bash
# 1. Suba a aplicação (em outro terminal, mantenha rodando)
JAVA_HOME=<caminho para um JDK 21> ./mvnw spring-boot:run

# 2. Prepare o ambiente Python (uma vez)
python3 -m venv robot/.venv
robot/.venv/bin/pip install -r robot/requirements.txt

# 3. Rode a suíte
robot/.venv/bin/robot -d robot/results robot/tests
```

Resultados em `robot/results/report.html` e `robot/results/log.html`.

`robot/.venv/` e `robot/results/` são gerados localmente e não entram no controle de versão.
