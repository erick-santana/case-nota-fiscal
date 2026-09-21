package br.com.itau.geradornotafiscal.adapter.in.web;

import br.com.itau.geradornotafiscal.model.Pedido;
import br.com.itau.geradornotafiscal.model.Regiao;
import br.com.itau.geradornotafiscal.model.TipoPessoa;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REQ-4.1: a cadeia protegida é o complemento de local/test, não uma lista de perfis conhecidos.
 * "qa" não é local/test, não tem {@code application-qa.properties} e não tem IdP configurado —
 * exatamente o caso de um ambiente novo que ninguém previu. Se a postura dependesse de listar
 * perfis, este teste vazaria como 200/401 mal configurado; aqui ele prova que a ausência de token
 * é suficiente para 401 mesmo sem nenhum {@code JwtDecoder} disponível.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("qa")
class PerfilNaoPrevistoFailClosedTest {

    private static final String ENDPOINT = "/api/pedido/gerarNotaFiscal";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void perfilNaoPrevistoSemTokenRetorna401() throws Exception {
        Pedido pedido = PedidoFixtures.umPedido(TipoPessoa.FISICA, null, 1500.0, Regiao.SUDESTE);

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido)))
                .andExpect(status().isUnauthorized());
    }
}
