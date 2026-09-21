*** Settings ***
Documentation     Keywords compartilhadas pela suíte E2E de geração de nota fiscal.
Library           RequestsLibrary
Library           OperatingSystem
Library           Collections

*** Variables ***
${BASE_URL}                 http://localhost:8080
${PAYLOADS_DIR}             ${CURDIR}/../../src/main/resources/paylods
${PAYLOADS_INVALIDOS_DIR}   ${CURDIR}/../resources/payloads_invalidos
${PAYLOADS_VALIDOS_DIR}     ${CURDIR}/../resources/payloads_validos

*** Keywords ***
Aguardar Aplicacao Disponivel
    Wait Until Keyword Succeeds    30s    2s    Create Session    api    ${BASE_URL}

Gerar Nota Fiscal A Partir Do Payload
    [Arguments]    ${nome_arquivo}
    ${payload}=    Get File    ${PAYLOADS_DIR}/${nome_arquivo}
    ${resposta}=    Enviar Pedido    ${payload}
    RETURN    ${resposta}

Enviar Pedido Invalido
    [Documentation]    Envia um fixture de robot/resources/payloads_invalidos/ — payload derivado
    ...    do contrato de entrada, mas quebrando uma regra de validação de SPEC-04.
    [Arguments]    ${nome_arquivo}
    ${payload}=    Get File    ${PAYLOADS_INVALIDOS_DIR}/${nome_arquivo}
    ${resposta}=    Enviar Pedido    ${payload}
    RETURN    ${resposta}

Enviar Pedido Valido No Limite
    [Documentation]    Envia um fixture de robot/resources/payloads_validos/ — payload derivado do
    ...    contrato de entrada, no limite (ou logo abaixo dele) de uma regra de validação de
    ...    SPEC-04, e que por isso deve ser aceito.
    [Arguments]    ${nome_arquivo}
    ${payload}=    Get File    ${PAYLOADS_VALIDOS_DIR}/${nome_arquivo}
    ${resposta}=    Enviar Pedido    ${payload}
    RETURN    ${resposta}

Enviar Pedido
    [Arguments]    ${payload}
    ${headers}=    Create Dictionary    Content-Type=application/json
    ${resposta}=    POST On Session    api    /api/pedido/gerarNotaFiscal    data=${payload}
    ...    headers=${headers}    expected_status=anything
    RETURN    ${resposta}

Corpo De Erro Deve Ser Seguro
    [Documentation]    REQ-4.8: a resposta de erro traz campo + motivo, nunca stack trace ou o
    ...    corpo bruto da exceção.
    [Arguments]    ${resposta}
    Should Not Contain    ${resposta.text}    Exception
    Should Not Contain    ${resposta.text}    \tat
    Dictionary Should Contain Key    ${resposta.json()}    mensagem
