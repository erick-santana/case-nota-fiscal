import http from 'k6/http';
import {check} from 'k6';
import {pedidoComNItens} from './lib/payloads.js';

// Cenário D: carga concorrente crescente (não só requisição única). Valida que a paralelização
// com virtual threads não degrada sob concorrência — cenário em que um pool de plataforma
// fixo teria enfileirado tarefas e feito a latência voltar a somar.
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    scenarios: {
        cargaConcorrente: {
            executor: 'ramping-vus',
            startVUs: 1,
            stages: [
                {duration: '10s', target: 20},
                {duration: '20s', target: 50},
                {duration: '10s', target: 0},
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<1500'],
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const res = http.post(`${BASE_URL}/api/pedido/gerarNotaFiscal`, JSON.stringify(pedidoComNItens(2)), {
        headers: {'Content-Type': 'application/json'},
    });
    check(res, {'status 200': (r) => r.status === 200});
}
