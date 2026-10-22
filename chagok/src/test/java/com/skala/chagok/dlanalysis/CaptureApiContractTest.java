package com.skala.chagok.dlanalysis;

import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.GlobalExceptionHandler;
import com.skala.chagok.global.response.ApiResponses;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CaptureApiContractTest {
    final CaptureService service = mock(CaptureService.class);
    final UUID warehouse = UUID.randomUUID(), event = UUID.randomUUID();
    final ApiResponses responses = new ApiResponses(new ChagokProperties(ZoneId.of("Asia/Seoul"),300,
            new ChagokProperties.Inventory(3,14),new ChagokProperties.BoxSize(10,50)));
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CaptureController(service,responses))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    String path() { return "/api/v1/warehouses/"+warehouse+"/captures"; }
    MockMultipartFile photo() { return new MockMultipartFile("images","camera.jpg","image/jpeg",new byte[]{1}); }

    @Test void acceptsThreeMultipartImagesAndReturnsEventLocation() throws Exception {
        when(service.upload(eq(warehouse),argThat(list -> list.size()==3)))
                .thenReturn(new CaptureService.Accepted(event.toString(),"PENDING",3));
        mvc.perform(multipart(path()).file(photo()).file(photo()).file(photo()))
                .andExpect(status().isAccepted()).andExpect(header().string("Location",path()+"/"+event))
                .andExpect(jsonPath("$.data.eventId").value(event.toString()))
                .andExpect(jsonPath("$.data.imageCount").value(3));
    }

    @Test void mapsUnavailableKafkaTo503InsteadOfInternalError() throws Exception {
        when(service.upload(eq(warehouse),anyList())).thenThrow(new DlAnalysisRequestPublishException(event.toString(),null));
        mvc.perform(multipart(path()).file(photo()).file(photo()).file(photo()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("TEMPORARILY_UNAVAILABLE"));
    }
}
