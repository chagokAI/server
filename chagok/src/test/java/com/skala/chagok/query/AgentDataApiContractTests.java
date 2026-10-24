package com.skala.chagok.query;

import com.skala.chagok.domain.agent.controller.AgentDataController;
import com.skala.chagok.domain.agent.dto.AgentDataDtos.*;
import com.skala.chagok.domain.agent.service.AgentDataService;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.*;
import com.skala.chagok.global.response.ApiResponses;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentDataApiContractTests {
    private final UUID warehouseId = UUID.randomUUID();
    private final AgentDataService service = mock(AgentDataService.class);
    private final ApiResponses responses = new ApiResponses(new ChagokProperties(ZoneId.of("Asia/Seoul"), 300,
            new ChagokProperties.Inventory(3, 14), new ChagokProperties.BoxSize(10, 50)));
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AgentDataController(service, responses, new com.skala.chagok.global.config.MatchingProperties(
                new com.skala.chagok.global.config.MatchingProperties.Tilted(true, 3))))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private String path(String suffix) { return "/internal/v1/warehouses/" + warehouseId + suffix; }

    @Test void monthlyShipmentsParsesMonthsAndStatuses() throws Exception {
        when(service.monthlyShipments(warehouseId, YearMonth.of(2025, 2), YearMonth.of(2026, 10), List.of("SUCCEEDED")))
                .thenReturn(List.of(new MonthlyShipment("2026-10", 4645)));
        mvc.perform(get(path("/monthly-shipments")).param("fromMonth", "2025-02").param("toMonth", "2026-10")
                        .param("statuses", "SUCCEEDED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].month").value("2026-10"))
                .andExpect(jsonPath("$.data[0].shipments").value(4645));
    }

    @Test void productShipmentsTakesIsoDates() throws Exception {
        when(service.productShipments(eq(warehouseId), eq(LocalDate.of(2026, 7, 23)), eq(LocalDate.of(2026, 10, 20)), isNull()))
                .thenReturn(List.of(new ProductShipment(UUID.randomUUID(), "P-1", "상품", "가공식품 > 과자류", 375, 278, 77, 342,
                        null, null, 120)));
        mvc.perform(get(path("/product-shipments")).param("from", "2026-07-23").param("to", "2026-10-20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].shipments").value(120))
                .andExpect(jsonPath("$.data[0].widthMm").value(375))
                .andExpect(jsonPath("$.data[0].weightG").value(342));
    }

    @Test void boxUsageReturnsActualAssignments() throws Exception {
        UUID productId = UUID.randomUUID();
        when(service.boxUsage(eq(warehouseId), eq(LocalDate.of(2026, 7, 23)), eq(LocalDate.of(2026, 10, 20))))
                .thenReturn(List.of(new BoxUsage(productId, "2호", 87, 4.0), new BoxUsage(productId, null, 3, null)));
        mvc.perform(get(path("/box-usage")).param("from", "2026-07-23").param("to", "2026-10-20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].boxCode").value("2호"))
                .andExpect(jsonPath("$.data[0].shipments").value(87))
                .andExpect(jsonPath("$.data[1].boxCode").isEmpty());
    }

    @Test void saveProposalReturnsIdAndItemCount() throws Exception {
        UUID proposalId = UUID.randomUUID();
        when(service.saveProposal(eq(warehouseId), eq(proposalId), any()))
                .thenReturn(new ProposalSaveResponse(proposalId, 1));
        mvc.perform(put(path("/procurement-proposals/" + proposalId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMonth\":\"2026-11\",\"summary\":{\"jobId\":\"job-1\"},"
                                + "\"generatedAt\":\"2026-10-21T01:00:00Z\",\"dataAsOf\":null,\"items\":[{\"boxSpecId\":\""
                                + UUID.randomUUID() + "\",\"onHandQty\":1,\"safetyQty\":2,\"forecastUsageQty\":3,"
                                + "\"proposedQty\":100,\"unitPriceKrw\":534,\"boxSnapshot\":{\"code\":\"4호\"}}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.proposalId").value(proposalId.toString()))
                .andExpect(jsonPath("$.data.itemCount").value(1));
    }

    @Test void foreignBoxSpecIsRejectedAsValidationError() throws Exception {
        UUID proposalId = UUID.randomUUID();
        when(service.saveProposal(eq(warehouseId), eq(proposalId), any()))
                .thenThrow(BusinessException.invalidField("items.boxSpecId", "센터에 없는 박스 규격이 있습니다."));
        mvc.perform(put(path("/procurement-proposals/" + proposalId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMonth\":\"2026-11\",\"summary\":{},\"generatedAt\":null,\"dataAsOf\":null,"
                                + "\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test void matchingPolicyReturnsServerSettings() throws Exception {
        mvc.perform(get("/internal/v1/matching-policy"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tiltedEnabled").value(true))
                .andExpect(jsonPath("$.data.tiltedClearanceMm").value(3));
    }
}
