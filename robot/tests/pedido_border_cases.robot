*** Settings ***
Documentation     Casos de sucesso no limite (ou logo abaixo) das regras de validação de SPEC-04:
...               cada payload é aceito porque está exatamente na borda válida de uma constraint,
...               não porque a viola. Complementa pedido_invalido.robot, que testa o outro lado da
...               mesma borda.
Resource          ../resources/api.resource
Suite Setup       Aguardar Aplicacao Disponivel

*** Test Cases ***
Itens Com Exatamente Cinquenta Itens Retorna 200
    [Documentation]    Borda superior de @Size(max = 50) em Pedido.itens — 50 é aceito, 51 (em
    ...                pedido_invalido.robot) não é.
    ${resp}=    Enviar Pedido Valido No Limite    itens_no_limite.json
    Should Be Equal As Numbers    ${resp.status_code}    200
    Length Should Be    ${resp.json()}[itens]    50

Valor Unitario Zero Retorna 200
    [Documentation]    Borda inferior de @PositiveOrZero em Item.valorUnitario — um item de
    ...                cortesia com valor zero é caso de negócio legítimo, contanto que
    ...                valor_total_itens permaneça positivo.
    ${resp}=    Enviar Pedido Valido No Limite    valor_unitario_zero.json
    Should Be Equal As Numbers    ${resp.status_code}    200

Valor Frete Zero Retorna 200
    [Documentation]    Borda inferior de @PositiveOrZero em Pedido.valorFrete.
    ${resp}=    Enviar Pedido Valido No Limite    valor_frete_zero.json
    Should Be Equal As Numbers    ${resp.status_code}    200

Quantidade Igual A Um Retorna 200
    [Documentation]    Borda inferior de @Positive em Item.quantidade.
    ${resp}=    Enviar Pedido Valido No Limite    quantidade_minima.json
    Should Be Equal As Numbers    ${resp.status_code}    200
    Should Be Equal As Numbers    ${resp.json()}[itens][0][quantidade]    1

Valor Total Itens Proximo Do Limite Da Tolerancia Retorna 200
    [Documentation]    D-04: a soma real dos itens é 100.0 e valor_total_itens é 100.009 — dentro
    ...                da tolerância de arredondamento de 0.01, então ainda é aceito.
    ${resp}=    Enviar Pedido Valido No Limite    valor_total_itens_proximo_do_limite_da_tolerancia.json
    Should Be Equal As Numbers    ${resp.status_code}    200

Endereco Sem Complemento Retorna 200
    [Documentation]    Endereco.complemento não tem constraint — é legitimamente opcional.
    ${resp}=    Enviar Pedido Valido No Limite    endereco_sem_complemento.json
    Should Be Equal As Numbers    ${resp.status_code}    200

Destinatario Com Enderecos Vazio Retorna 200
    [Documentation]    Contraste com "Destinatario Sem Enderecos Retorna 400" de
    ...                pedido_invalido.robot: lista vazia (não nula) é o caso de negócio "sem
    ...                endereço de entrega" — frete sai zerado, mas o pedido é aceito.
    ${resp}=    Enviar Pedido Valido No Limite    destinatario_com_enderecos_vazio.json
    Should Be Equal As Numbers    ${resp.status_code}    200
    Should Be Equal As Numbers    ${resp.json()}[valor_frete]    0
