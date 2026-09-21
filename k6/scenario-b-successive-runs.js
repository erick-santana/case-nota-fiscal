import http from 'k6/http';
import {check} from 'k6';
import {Trend} from 'k6/metrics';
import exec from 'k6/execution';
import {pedidoComNItens} from './lib/payloads.js';

// REQ-3.1 / cenário B: execuções sucessivas sob carga constante não podem degradar ao longo do
// tempo — é o sintoma do antigo CalculadoraAliquotaProduto.itemNotaFiscalList estático, que
// acumulava itens de requisição em requisição. Compara p95 do primeiro terço com o último terço.
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOTAL_ITERACOES = 300;

export const latenciaPrimeiroTerco = new Trend('latencia_primeiro_terco', true);
export const latenciaUltimoTerco = new Trend('latencia_ultimo_terco', true);

export const options = {
    scenarios: {
        cargaConstante: {
            executor: 'shared-iterations',
            vus: 10,
            iterations: TOTAL_ITERACOES,
            maxDuration: '2m',
        },
    },
    thresholds: {
        latencia_primeiro_terco: ['p(95)<1500'],
        latencia_ultimo_terco: ['p(95)<1500'],
    },
};

export default function () {
    const res = http.post(`${BASE_URL}/api/pedido/gerarNotaFiscal`, JSON.stringify(pedidoComNItens(3)), {
        headers: {'Content-Type': 'application/json'},
    });
    check(res, {'status 200': (r) => r.status === 200});

    const iteracao = exec.scenario.iterationInTest;
    if (iteracao < TOTAL_ITERACOES / 3) {
        latenciaPrimeiroTerco.add(res.timings.duration);
    } else if (iteracao >= (TOTAL_ITERACOES * 2) / 3) {
        latenciaUltimoTerco.add(res.timings.duration);
    }
}
