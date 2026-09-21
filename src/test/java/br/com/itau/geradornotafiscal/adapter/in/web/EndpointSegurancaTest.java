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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercita a cadeia JWT real (perfil "dev", não a permissiva de local/test) — REQ-4.1/REQ-4.2 de
 * SPEC-04. Sem {@code OIDC_ISSUER_URI} no ambiente de teste, {@code SecurityConfig.jwtDecoder}
 * devolveria o decoder que rejeita tudo; o {@code @MockitoBean} substitui esse bean para controlar
 * decode com sucesso/falha, sem depender de rede alguma.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class EndpointSegurancaTest {

    private static final String ENDPOINT = "/api/pedido/gerarNotaFiscal";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void semHeaderAuthorizationRetorna401() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(umPedido())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenInvalidoRetorna401() throws Exception {
        when(jwtDecoder.decode("token-invalido")).thenThrow(new BadJwtException("token inválido"));

        mockMvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer token-invalido")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(umPedido())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenValidoEPayloadValidoRetorna200() throws Exception {
        when(jwtDecoder.decode("token-valido")).thenReturn(umJwtValido());

        mockMvc.perform(post(ENDPOINT)
                        .header("Authorization", "Bearer token-valido")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(umPedido())))
                .andExpect(status().isOk());
    }

    private Jwt umJwtValido() {
        Instant agora = Instant.now();
        return new Jwt("token-valido", agora, agora.plusSeconds(300), Map.of("alg", "none"), Map.of("sub", "teste"));
    }

    private Pedido umPedido() {
        return PedidoFixtures.umPedido(TipoPessoa.FISICA, null, 1500.0, Regiao.SUDESTE);
    }
}
